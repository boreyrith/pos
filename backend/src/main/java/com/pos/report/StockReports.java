package com.pos.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.pos.report.Report.Column;
import com.pos.report.ReportsService.Scope;

/**
 * The stock and purchasing reports: inventory valuation, stock movements, purchases by vendor and
 * what is owed to vendors by age. Amounts are in the base currency ({@code base}).
 */
@Component
class StockReports {

    private final JdbcClient jdbc;

    StockReports(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Report inventoryValuation(Scope s, Long categoryId, String base) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT o.name, o.sku, o.type, COALESCE(c.name, 'Uncategorised') AS category, u.symbol AS unit,
                               SUM(l.quantity) AS qty, o.cost, o.price, o.low_stock_threshold AS threshold
                        FROM offerings o JOIN stock_levels l ON l.offering_id = o.id JOIN storages st ON st.id = l.storage_id
                        LEFT JOIN categories c ON c.id = o.category_id LEFT JOIN units u ON u.id = o.unit_id
                        WHERE o.type IN ('PRODUCT', 'ITEM') AND st.active AND (:branch IS NULL OR st.branch_id = :branch)
                          AND (:category IS NULL OR o.category_id = :category)
                        GROUP BY o.id, o.name, o.sku, o.type, c.name, u.symbol, o.cost, o.price, o.low_stock_threshold
                        HAVING qty <> 0
                        ORDER BY qty * o.cost DESC
                        """)
                .params(ReportsService.with(s.params(), "category", categoryId))
                .query((rs, n) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    long qty = rs.getLong("qty");
                    BigDecimal cost = rs.getBigDecimal("cost");
                    BigDecimal price = rs.getBigDecimal("price");
                    r.put("name", rs.getString("name"));
                    r.put("sku", rs.getString("sku"));
                    r.put("category", rs.getString("category"));
                    r.put("qty", qty);
                    r.put("unit", rs.getString("unit"));
                    r.put("cost", cost);
                    r.put("value", ReportsService.money(cost.multiply(BigDecimal.valueOf(qty))));
                    r.put("price", price);
                    r.put("retail", ReportsService.money(price.multiply(BigDecimal.valueOf(qty))));
                    r.put("margin", ReportsService.pct(price.subtract(cost), price));
                    r.put("low", qty <= rs.getInt("threshold") ? "Low" : "");
                    return r;
                })
                .list();
        return new Report("inventory-valuation", "Stock valuation", "As of now", List.of(Column.text("name", "Item"),
                Column.text("sku", "SKU"), Column.text("category", "Category"), Column.integer("qty", "On hand"), Column.text("unit", "Unit"),
                Column.money("cost", "Cost each"), Column.money("value", "Value at cost"), Column.money("price", "Price each"),
                Column.money("retail", "Value at price"), Column.percent("margin", "Margin"), Column.text("low", "Stock")),
                ReportsService.withShare(rows, "value"), ReportsService.sumTotals(rows, "name", List.of("qty", "value", "retail"), null),
                List.of("Stock in active storages right now, at each item's current cost and selling price (VAT-included prices include VAT)."), base);
    }

    Report stockMovements(Scope s, String base) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT m.type, COUNT(*) AS n, SUM(IF(m.quantity > 0, m.quantity, 0)) AS qin, SUM(IF(m.quantity < 0, -m.quantity, 0)) AS qout,
                               SUM(m.quantity * f.cost) AS value
                        FROM stock_movements m JOIN storages st ON st.id = m.storage_id JOIN offerings f ON f.id = m.offering_id
                        WHERE m.created_at >= :start AND m.created_at < :end AND (:branch IS NULL OR st.branch_id = :branch)
                        GROUP BY m.type
                        ORDER BY ABS(SUM(m.quantity * f.cost)) DESC
                        """)
                .params(s.params())
                .query((rs, n) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("type", movementLabel(rs.getString("type")));
                    r.put("movements", rs.getLong("n"));
                    r.put("in", rs.getLong("qin"));
                    r.put("out", rs.getLong("qout"));
                    r.put("net", rs.getLong("qin") - rs.getLong("qout"));
                    r.put("value", ReportsService.money(rs.getBigDecimal("value")));
                    return r;
                })
                .list();
        return new Report("stock-movements", "Stock movements", null, List.of(Column.text("type", "Kind"), Column.integer("movements", "Lines"),
                Column.integer("in", "Units in"), Column.integer("out", "Units out"), Column.integer("net", "Net"),
                Column.money("value", "Value change (at current cost)")),
                rows, ReportsService.sumTotals(rows, "type", List.of("movements", "in", "out", "net", "value"), null),
                List.of("Base units. Transfers within the selected scope show as both out and in. Values use today's cost."), base);
    }

    Report purchasesByVendor(Scope s, String base) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT v.code, v.name,
                          (SELECT COUNT(*) FROM purchase_orders p WHERE p.vendor_id = v.id AND p.ordered_at >= :start AND p.ordered_at < :end
                             AND (:branch IS NULL OR p.branch_id = :branch)) AS placed,
                          (SELECT COALESCE(SUM(p.total), 0) FROM purchase_orders p WHERE p.vendor_id = v.id AND p.ordered_at >= :start
                             AND p.ordered_at < :end AND (:branch IS NULL OR p.branch_id = :branch)) AS placed_value,
                          (SELECT COALESCE(SUM(gl.quantity * gl.unit_cost), 0) FROM goods_receipt_lines gl
                             JOIN goods_receipts g ON g.id = gl.goods_receipt_id JOIN purchase_orders p ON p.id = g.purchase_order_id
                             WHERE p.vendor_id = v.id AND g.received_at >= :start AND g.received_at < :end
                             AND (:branch IS NULL OR p.branch_id = :branch)) AS received,
                          (SELECT COALESCE(SUM(i.total), 0) FROM vendor_invoices i LEFT JOIN purchase_orders p ON p.id = i.purchase_order_id
                             WHERE i.vendor_id = v.id AND i.status <> 'VOID' AND i.invoice_date BETWEEN :fromDate AND :toDate
                             AND (:branch IS NULL OR p.branch_id = :branch)) AS invoiced,
                          (SELECT COALESCE(SUM(i.tax), 0) FROM vendor_invoices i LEFT JOIN purchase_orders p ON p.id = i.purchase_order_id
                             WHERE i.vendor_id = v.id AND i.status <> 'VOID' AND i.invoice_date BETWEEN :fromDate AND :toDate
                             AND (:branch IS NULL OR p.branch_id = :branch)) AS input_tax,
                          (SELECT COALESCE(SUM(vp.amount), 0) FROM vendor_payments vp JOIN vendor_invoices i ON i.id = vp.vendor_invoice_id
                             LEFT JOIN purchase_orders p ON p.id = i.purchase_order_id
                             WHERE i.vendor_id = v.id AND vp.paid_on BETWEEN :fromDate AND :toDate AND (:branch IS NULL OR p.branch_id = :branch)) AS paid,
                          (SELECT COALESCE(SUM(i.total - i.paid), 0) FROM vendor_invoices i LEFT JOIN purchase_orders p ON p.id = i.purchase_order_id
                             WHERE i.vendor_id = v.id AND i.status IN ('UNPAID', 'PARTIALLY_PAID') AND (:branch IS NULL OR p.branch_id = :branch)) AS owed
                        FROM vendors v
                        ORDER BY received DESC, v.name
                        """)
                .params(s.params())
                .query((rs, n) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("vendor", rs.getString("name"));
                    r.put("code", rs.getString("code"));
                    r.put("placed", rs.getLong("placed"));
                    r.put("placedValue", rs.getBigDecimal("placed_value"));
                    r.put("received", ReportsService.money(rs.getBigDecimal("received")));
                    r.put("invoiced", rs.getBigDecimal("invoiced"));
                    r.put("inputTax", rs.getBigDecimal("input_tax"));
                    r.put("paid", rs.getBigDecimal("paid"));
                    r.put("owed", rs.getBigDecimal("owed"));
                    return r;
                })
                .list()
                .stream()
                .filter(r -> r.values().stream().anyMatch(v -> (v instanceof BigDecimal b && b.signum() != 0) || (v instanceof Long l && l != 0)))
                .toList();
        return new Report("purchases-by-vendor", "Purchases by vendor", null, List.of(Column.text("vendor", "Vendor"),
                Column.text("code", "Code"), Column.integer("placed", "Orders placed"), Column.money("placedValue", "Ordered value"),
                Column.money("received", "Received value"), Column.money("invoiced", "Invoiced"), Column.money("inputTax", "Input tax"),
                Column.money("paid", "Paid"), Column.money("owed", "Owed now")),
                rows, ReportsService.sumTotals(rows, "vendor", List.of("placed", "placedValue", "received", "invoiced", "inputTax", "paid", "owed"), null),
                List.of("Ordered by when orders were placed, received by delivery date, invoiced by invoice date, paid by payment date. "
                        + "Received value is at the order's cost, before tax. \"Owed now\" is today's unpaid balance."), base);
    }

    Report payablesAging(Scope s, String base) {
        LocalDate today = LocalDate.now(s.zone());
        Map<String, Map<String, Object>> byVendor = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT v.name, v.code, i.total - i.paid AS bal, DATEDIFF(:today, i.due_date) AS late
                        FROM vendor_invoices i JOIN vendors v ON v.id = i.vendor_id LEFT JOIN purchase_orders p ON p.id = i.purchase_order_id
                        WHERE i.status IN ('UNPAID', 'PARTIALLY_PAID') AND (:branch IS NULL OR p.branch_id = :branch)
                        ORDER BY v.name
                        """)
                .params(ReportsService.with(s.params(), "today", today))
                .query((rs, n) -> {
                    Map<String, Object> r = byVendor.computeIfAbsent(rs.getString("code"), k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("vendor", rsName(rs));
                        m.put("invoices", 0L);
                        for (String b : List.of("current", "d30", "d60", "d90", "over90", "total")) {
                            m.put(b, ReportsService.z());
                        }
                        return m;
                    });
                    BigDecimal bal = rs.getBigDecimal("bal");
                    int late = rs.getInt("late");
                    String bucket = late <= 0 ? "current" : late <= 30 ? "d30" : late <= 60 ? "d60" : late <= 90 ? "d90" : "over90";
                    r.put(bucket, ((BigDecimal) r.get(bucket)).add(bal));
                    r.put("total", ((BigDecimal) r.get("total")).add(bal));
                    r.put("invoices", (Long) r.get("invoices") + 1);
                    return null;
                })
                .list();
        List<Map<String, Object>> rows = new ArrayList<>(byVendor.values());
        rows.sort((a, b) -> ((BigDecimal) b.get("total")).compareTo((BigDecimal) a.get("total")));
        return new Report("payables-aging", "Payables aging", "As of " + today, List.of(Column.text("vendor", "Vendor"),
                Column.integer("invoices", "Unpaid invoices"), Column.money("current", "Not yet due"), Column.money("d30", "1–30 days late"),
                Column.money("d60", "31–60"), Column.money("d90", "61–90"), Column.money("over90", "Over 90"), Column.money("total", "Owed")),
                rows, ReportsService.sumTotals(rows, "vendor", List.of("invoices", "current", "d30", "d60", "d90", "over90", "total"), null),
                List.of("Unpaid and partly paid vendor invoices by how far past their due date they are, today."), base);
    }

    private static String rsName(java.sql.ResultSet rs) {
        try {
            return rs.getString("name");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String movementLabel(String t) {
        return switch (t) {
            case "OPENING" -> "Opening stock";
            case "SALE" -> "Sales";
            case "SALE_RETURN" -> "Unpaid sales returned";
            case "REFUND" -> "Customer returns";
            case "PURCHASE" -> "Purchases received";
            case "RECEIVE" -> "Received (manual)";
            case "REMOVE" -> "Removed (manual)";
            case "COUNT" -> "Stock count differences";
            case "ADJUSTMENT_IN" -> "Adjustments in";
            case "ADJUSTMENT_OUT" -> "Adjustments out";
            case "TRANSFER_OUT" -> "Transfers out";
            case "TRANSFER_IN" -> "Transfers in";
            case "TRANSFER_RETURN" -> "Transfers cancelled";
            default -> t;
        };
    }
}
