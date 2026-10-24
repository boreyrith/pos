package com.pos.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TreeMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.catalog.OfferingType;
import com.pos.settings.SettingsService;
import com.pos.currency.CurrencyService;
import com.pos.report.ReportDtos.CashByCurrency;
import com.pos.report.ReportDtos.DailySales;
import com.pos.report.ReportDtos.Dashboard;
import com.pos.report.ReportDtos.PaymentBreakdown;
import com.pos.report.ReportDtos.TopSeller;
import com.pos.report.ReportDtos.Kpis;
import com.pos.report.ReportDtos.LowStockItem;
import com.pos.report.ReportDtos.Payables;
import com.pos.report.ReportDtos.TopCustomer;

/**
 * The dashboard. Figures come from the same engine as the Reports module ({@link ReportsService}),
 * so they always agree: sales count by when they were paid, refunds by when they were made, cost is
 * what each line cost at the time of sale, and days are the store's time zone.
 */
@Service
public class ReportService {

    /** Orders that were paid; refunds are subtracted separately by their own date. */
    static final String SOLD = "'COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED'";

    private final JdbcClient jdbc;
    private final SettingsService settings;
    private final CurrencyService currencies;
    private final ReportsService reports;

    public ReportService(JdbcClient jdbc, SettingsService settings, CurrencyService currencies, @Lazy ReportsService reports) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.currencies = currencies;
        this.reports = reports;
    }

    /** The store's UTC offset as MySQL wants it, e.g. +07:00 (for the tax report's daily rows). */
    String offset() {
        return settings.current().timezone().getRules().getOffset(Instant.now()).getId().replace("Z", "+00:00");
    }

    /**
     * {@code from}..{@code to} (store-local, inclusive); {@code branchId} null means every branch.
     * {@code withPurchasing} adds what is owed to vendors.
     */
    @Transactional(readOnly = true)
    public Dashboard dashboard(LocalDate from, LocalDate to, Long branchId, boolean withPurchasing) {
        ZoneId zone = settings.current().timezone();
        LocalDate today = LocalDate.now(zone);
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        var scope = reports.scope(from, to, branchId);
        var p = scope.params();

        TreeMap<String, ReportsService.Period> byDay = reports.periods(scope, t -> t.toLocalDate().toString());
        List<DailySales> daily = from.datesUntil(to.plusDays(1)).map(d -> {
            var x = byDay.get(d.toString());
            return x == null ? new DailySales(d, ReportsService.z(), 0, ReportsService.z())
                    : new DailySales(d, x.salesAfterReturns(), x.orders(), x.profit());
        }).toList();

        Kpis period = kpis(byDay.values().stream().reduce(ReportsService.Period.empty("p"), ReportsService.Period::plus));
        var prevScope = reports.scope(from.minusDays(days), from.minusDays(1), branchId);
        Kpis previous = kpis(reports.periods(prevScope, t -> "p").getOrDefault("p", ReportsService.Period.empty("p")));
        var todayScope = reports.scope(today, today, branchId);
        Kpis todayKpis = kpis(reports.periods(todayScope, t -> "p").getOrDefault("p", ReportsService.Period.empty("p")));

        List<TopSeller> top = jdbc.sql("""
                        SELECT i.offering_id, MAX(i.offering_type) AS type, MAX(i.name) AS name,
                               SUM(i.quantity - i.refunded_qty) AS qty,
                               SUM(i.line_net - (i.refunded_amount - i.refunded_tax)) AS net,
                               SUM(i.line_net - (i.refunded_amount - i.refunded_tax) - i.cost_total * (i.quantity - i.refunded_qty) / i.quantity) AS profit
                        FROM order_items i JOIN orders o ON o.id = i.order_id
                        WHERE o.status IN (%s) AND o.completed_at >= :start AND o.completed_at < :end
                          AND (:branch IS NULL OR o.branch_id = :branch)
                        GROUP BY i.offering_id
                        HAVING net > 0
                        ORDER BY net DESC
                        LIMIT 5
                        """.formatted(SOLD))
                .params(p)
                .query((rs, n) -> new TopSeller(rs.getLong("offering_id"), OfferingType.valueOf(rs.getString("type")), rs.getString("name"),
                        rs.getLong("qty"), ReportsService.money(rs.getBigDecimal("net")), ReportsService.money(rs.getBigDecimal("profit"))))
                .list();

        // Money taken by when it was paid; payments on orders refunded later still count (the refund is separate).
        List<PaymentBreakdown> payments = jdbc.sql("""
                        SELECT p.method, SUM(p.amount) AS amount, COUNT(*) AS cnt
                        FROM payments p JOIN orders o ON o.id = p.order_id
                        WHERE p.status IN ('PAID', 'REFUNDED') AND p.paid_at >= :start AND p.paid_at < :end
                          AND (:branch IS NULL OR o.branch_id = :branch)
                        GROUP BY p.method
                        ORDER BY amount DESC
                        """)
                .params(p)
                .query((rs, n) -> new PaymentBreakdown(rs.getString("method"), rs.getBigDecimal("amount"), rs.getLong("cnt")))
                .list();
        BigDecimal refunds = period.refunds();

        List<CashByCurrency> cash = jdbc.sql("""
                        SELECT currency, SUM(received) AS received, SUM(change_given) AS change_given
                        FROM (
                            SELECT t.currency_code AS currency, t.amount AS received, 0 AS change_given
                            FROM payment_tenders t JOIN payments p ON p.id = t.payment_id JOIN orders o ON o.id = p.order_id
                            WHERE p.status IN ('PAID', 'REFUNDED') AND p.paid_at >= :start AND p.paid_at < :end
                              AND (:branch IS NULL OR o.branch_id = :branch)
                            UNION ALL
                            SELECT p.change_currency, 0, p.change_amount
                            FROM payments p JOIN orders o ON o.id = p.order_id
                            WHERE p.method = 'CASH' AND p.change_amount > 0 AND p.status IN ('PAID', 'REFUNDED')
                              AND p.paid_at >= :start AND p.paid_at < :end AND (:branch IS NULL OR o.branch_id = :branch)
                        ) cash
                        GROUP BY currency
                        ORDER BY currency
                        """)
                .params(p)
                .query((rs, n) -> {
                    BigDecimal received = rs.getBigDecimal("received").stripTrailingZeros();
                    BigDecimal change = rs.getBigDecimal("change_given").stripTrailingZeros();
                    return new CashByCurrency(rs.getString("currency"), received, change, received.subtract(change).stripTrailingZeros());
                })
                .list();

        List<LowStockItem> low = jdbc.sql("""
                        SELECT o.id, o.name, u.symbol AS unit, o.low_stock_threshold AS threshold,
                               (SELECT COALESCE(SUM(l.quantity), 0) FROM stock_levels l JOIN storages s ON s.id = l.storage_id
                                WHERE l.offering_id = o.id AND s.active AND (:branch IS NULL OR s.branch_id = :branch)) AS stock
                        FROM offerings o LEFT JOIN units u ON u.id = o.unit_id
                        WHERE o.active AND o.type IN ('PRODUCT', 'ITEM')
                        HAVING stock <= threshold
                        ORDER BY stock - threshold, stock, o.name
                        """)
                .params(p)
                .query((rs, n) -> new LowStockItem(rs.getLong("id"), rs.getString("name"), rs.getString("unit"), rs.getLong("stock"),
                        rs.getInt("threshold")))
                .list();

        long pending = jdbc.sql("SELECT COUNT(*) FROM orders WHERE status = 'PENDING_PAYMENT' AND (:branch IS NULL OR branch_id = :branch)")
                .params(p).query(Long.class).single();
        long incoming = jdbc.sql("""
                        SELECT COUNT(*) FROM stock_transfers t JOIN storages s ON s.id = t.to_storage_id
                        WHERE t.status = 'IN_TRANSIT' AND (:branch IS NULL OR s.branch_id = :branch)
                        """).params(p).query(Long.class).single();
        long newCustomers = jdbc.sql("SELECT COUNT(*) FROM customers WHERE NOT is_default AND created_at >= :start AND created_at < :end")
                .params(p).query(Long.class).single();
        List<TopCustomer> customers = jdbc.sql("""
                        SELECT c.id, c.name, COUNT(*) AS orders, SUM(o.total - o.refunded_total) AS spent
                        FROM orders o JOIN customers c ON c.id = o.customer_id
                        WHERE NOT c.is_default AND o.status IN (%s) AND o.completed_at >= :start AND o.completed_at < :end
                          AND (:branch IS NULL OR o.branch_id = :branch)
                        GROUP BY c.id, c.name
                        HAVING spent > 0
                        ORDER BY spent DESC
                        LIMIT 5
                        """.formatted(SOLD))
                .params(p)
                .query((rs, n) -> new TopCustomer(rs.getLong("id"), rs.getString("name"), rs.getLong("orders"), rs.getBigDecimal("spent")))
                .list();

        Payables payables = null;
        if (withPurchasing) {
            payables = jdbc.sql("""
                            SELECT (SELECT COALESCE(SUM(i.total - i.paid), 0) FROM vendor_invoices i LEFT JOIN purchase_orders po ON po.id = i.purchase_order_id
                                    WHERE i.status IN ('UNPAID', 'PARTIALLY_PAID') AND (:branch IS NULL OR po.branch_id = :branch)) AS owed,
                                   (SELECT COALESCE(SUM(i.total - i.paid), 0) FROM vendor_invoices i LEFT JOIN purchase_orders po ON po.id = i.purchase_order_id
                                    WHERE i.status IN ('UNPAID', 'PARTIALLY_PAID') AND i.due_date < :today AND (:branch IS NULL OR po.branch_id = :branch)) AS overdue,
                                   (SELECT COUNT(*) FROM purchase_orders po WHERE po.status IN ('ORDERED', 'PARTIALLY_RECEIVED')
                                    AND (:branch IS NULL OR po.branch_id = :branch)) AS open_orders
                            """)
                    .params(p).param("today", today)
                    .query((rs, n) -> new Payables(rs.getBigDecimal("owed"), rs.getBigDecimal("overdue"), rs.getLong("open_orders")))
                    .single();
        }

        return new Dashboard(currencies.base().code(), from, to, todayKpis, period, previous, daily, top, payments, refunds, cash,
                low.size(), low.stream().limit(8).toList(), pending, incoming, newCustomers, customers, payables);
    }

    private static Kpis kpis(ReportsService.Period x) {
        BigDecimal avg = x.orders() == 0 ? ReportsService.z()
                : x.total().divide(BigDecimal.valueOf(x.orders()), 2, java.math.RoundingMode.HALF_UP);
        return new Kpis(x.orders(), x.items(), x.salesAfterReturns(), x.netAfterReturns(), x.tax().subtract(x.returnsTax()), x.discounts(),
                x.returns(), x.returnsTotal(), x.costAfterReturns(), x.profit(), x.margin(), avg);
    }
}
