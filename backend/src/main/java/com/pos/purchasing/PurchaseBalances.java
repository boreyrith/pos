package com.pos.purchasing;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.pos.accounting.OpenPurchases;

/** Unpaid vendor invoices and deliveries not yet invoiced, for the books' opening balances. */
@Component
class PurchaseBalances implements OpenPurchases {

    private final JdbcClient jdbc;

    PurchaseBalances(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<VendorOwed> owedByVendor() {
        return jdbc.sql("""
                        SELECT v.id, v.name, SUM(i.total - i.paid) AS owed FROM vendor_invoices i JOIN vendors v ON v.id = i.vendor_id
                        WHERE i.status IN ('UNPAID', 'PARTIALLY_PAID') GROUP BY v.id, v.name HAVING owed > 0 ORDER BY v.name
                        """)
                .query((rs, i) -> new VendorOwed(rs.getLong("id"), rs.getString("name"), rs.getBigDecimal("owed")))
                .list();
    }

    @Override
    public BigDecimal receivedNotInvoiced() {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(GREATEST(r.received - COALESCE(inv.invoiced, 0), 0)), 0) FROM (
                            SELECT po.id, ROUND(SUM(l.unit_cost * l.received_qty), 2) AS received FROM purchase_orders po
                            JOIN purchase_order_lines l ON l.purchase_order_id = po.id
                            WHERE po.status NOT IN ('DRAFT', 'CANCELLED') GROUP BY po.id
                        ) r LEFT JOIN (
                            SELECT purchase_order_id, SUM(subtotal) AS invoiced FROM vendor_invoices
                            WHERE status <> 'VOID' AND purchase_order_id IS NOT NULL GROUP BY purchase_order_id
                        ) inv ON inv.purchase_order_id = r.id
                        """).query(BigDecimal.class).single();
    }
}
