package com.pos.sales;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Component;

/**
 * Finds orders by text: any part of the order number or of the customer name on the order (through
 * the ngram full-text index {@code ft_order_search}, V33), or of the customer's phone or code. Text
 * of one character can't use the index (its pieces are two characters) and is left to
 * {@link OrderRepository#search}.
 */
@Component
class OrderSearch {

    /** Shortest text the full-text index can find. */
    static final int MIN_LENGTH = 2;

    private final JdbcClient jdbc;
    private final OrderRepository orders;

    OrderSearch(JdbcClient jdbc, OrderRepository orders) {
        this.jdbc = jdbc;
        this.orders = orders;
    }

    /** Matching orders, newest first; {@code branchId}, status, times and customer null mean any. */
    Page<Order> search(Long branchId, OrderStatus status, Instant from, Instant to, String q, Long customerId, Pageable pageable) {
        // The matches first (index lookups), then their orders: STRAIGHT_JOIN keeps that order, so the
        // work grows with the matches, not with all orders.
        String matches = """
                FROM (SELECT id FROM orders WHERE MATCH(order_number, customer_name) AGAINST (:phrase IN BOOLEAN MODE)
                      UNION
                      SELECT m.id FROM orders m JOIN customers c ON c.id = m.customer_id
                      WHERE c.phone LIKE :like ESCAPE '!' OR c.code LIKE :like ESCAPE '!') found
                STRAIGHT_JOIN orders o ON o.id = found.id
                WHERE (:branch IS NULL OR o.branch_id = :branch)
                  AND (:status IS NULL OR o.status = :status)
                  AND (:from IS NULL OR o.created_at >= :from)
                  AND (:to IS NULL OR o.created_at < :to)
                  AND (:customer IS NULL OR o.customer_id = :customer)
                """;
        long total = params(jdbc.sql("SELECT COUNT(*) " + matches), branchId, status, from, to, q, customerId)
                .query(Long.class).single();
        if (total == 0) {
            return Page.empty(pageable);
        }
        List<Long> ids = params(jdbc.sql("SELECT o.id " + matches + " ORDER BY o.id DESC LIMIT :size OFFSET :offset"),
                branchId, status, from, to, q, customerId)
                .param("size", pageable.getPageSize())
                .param("offset", pageable.getOffset())
                .query(Long.class).list();
        Map<Long, Integer> position = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            position.put(ids.get(i), i);
        }
        List<Order> page = orders.findByIdIn(ids).stream().sorted(Comparator.comparing(o -> position.get(o.getId()))).toList();
        return new PageImpl<>(page, pageable, total);
    }

    private static StatementSpec params(StatementSpec sql, Long branchId, OrderStatus status, Instant from, Instant to, String q,
                                        Long customerId) {
        return sql.param("phrase", phrase(q))
                .param("like", "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%")
                .param("branch", branchId)
                .param("status", status == null ? null : status.name())
                .param("from", from)
                .param("to", to)
                .param("customer", customerId);
    }

    /** The text as one boolean-mode phrase: its pieces next to each other, operators taken literally. */
    static String phrase(String q) {
        return "\"" + q.replace("\"", " ").strip() + "\"";
    }
}
