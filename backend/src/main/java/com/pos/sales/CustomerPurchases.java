package com.pos.sales;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.pos.customer.CustomerService.CustomerStats;
import com.pos.customer.PurchaseHistory;

/** Totals a customer's paid orders (completed, partly or fully refunded) for the customer page. */
@Component
class CustomerPurchases implements PurchaseHistory {

    private final JdbcClient jdbc;

    CustomerPurchases(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CustomerStats stats(Long customerId) {
        return jdbc.sql("""
                        SELECT COUNT(*) AS orders, COALESCE(SUM(total), 0) AS gross, COALESCE(SUM(refunded_total), 0) AS refunded,
                               MIN(completed_at) AS first_visit, MAX(completed_at) AS last_visit
                        FROM orders WHERE customer_id = :id AND status IN ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')
                        """)
                .param("id", customerId)
                .query((rs, n) -> new CustomerStats(rs.getLong("orders"), rs.getBigDecimal("gross").subtract(rs.getBigDecimal("refunded")),
                        rs.getBigDecimal("refunded"),
                        rs.getTimestamp("first_visit") == null ? null : rs.getTimestamp("first_visit").toInstant(),
                        rs.getTimestamp("last_visit") == null ? null : rs.getTimestamp("last_visit").toInstant()))
                .single();
    }
}
