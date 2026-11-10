package com.pos.sales;

import java.time.Instant;
import java.util.LinkedHashMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.pos.catalog.SalesRanking;

/** Ranks catalog entries by units kept in a branch's paid orders, for the top sellers. */
@Component
class SalesRankingQuery implements SalesRanking {

    private final JdbcClient jdbc;

    SalesRankingQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LinkedHashMap<Long, Long> unitsSold(Long branchId, Instant since, int max) {
        LinkedHashMap<Long, Long> sold = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT i.offering_id, SUM(i.quantity - i.refunded_qty) AS units
                        FROM order_items i JOIN orders o ON o.id = i.order_id
                        WHERE o.branch_id = :branch AND o.status IN ('COMPLETED', 'PARTIALLY_REFUNDED')
                          AND o.created_at >= :since
                        GROUP BY i.offering_id
                        HAVING units > 0
                        ORDER BY units DESC, i.offering_id
                        LIMIT :max
                        """)
                .param("branch", branchId)
                .param("since", since)
                .param("max", max)
                .query((rs, n) -> sold.put(rs.getLong(1), rs.getLong(2)))
                .list();
        return sold;
    }
}
