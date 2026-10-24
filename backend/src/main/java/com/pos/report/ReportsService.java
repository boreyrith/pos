package com.pos.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.ApiException;
import com.pos.currency.CurrencyService;
import com.pos.report.Report.Column;
import com.pos.settings.SettingsService;

/**
 * The Reports module. Every report is read-only SQL over what sales, returns, stock and
 * purchasing saved at the time, so past periods never change when prices, costs or settings do.
 * <p>Conventions, the same in every report:
 * <ul>
 * <li>Sales count by the time they were paid; returns by the time they were made (summary,
 * dashboard), except reports per item / category / cashier / customer / branch, which net each
 * sale's returns against the sale itself so a row is "what that sale finally came to".</li>
 * <li>"Net sales" are without tax, after discounts. "Total" is what customers paid, tax included.</li>
 * <li>Cost is the cost saved on each line at the time of sale; profit = net sales - cost.</li>
 * <li>Days, weeks, months and hours are the store's time zone (daylight saving included).</li>
 * </ul>
 */
@Service
public class ReportsService {

    public static final List<Report.Info> CATALOG = List.of(
            new Report.Info("sales-summary", "Sales summary", "Sales",
                    "Orders, discounts, tax, returns, cost and profit per day, week or month", "REPORTS_SALES", true),
            new Report.Info("sales-by-item", "Sales by item", "Sales",
                    "Quantity, net sales, cost, profit and margin for each product, service, item and package", "REPORTS_SALES", true),
            new Report.Info("sales-by-category", "Sales by category", "Sales", "Net sales, cost and profit per category", "REPORTS_SALES", true),
            new Report.Info("sales-by-time", "Sales by hour / weekday", "Sales", "When the shop is busy", "REPORTS_SALES", true),
            new Report.Info("sales-by-cashier", "Sales by cashier", "Sales", "Orders, sales, discounts and refunds per cashier", "REPORTS_SALES", true),
            new Report.Info("services-by-staff", "Services by staff", "Sales", "Jobs, hours and revenue per person doing the work", "REPORTS_SALES", true),
            new Report.Info("sales-by-customer", "Sales by customer", "Sales", "What each customer bought", "REPORTS_SALES", true),
            new Report.Info("sales-by-branch", "Sales by branch", "Sales", "Sales, returns, cost and profit per branch", "REPORTS_SALES", true),
            new Report.Info("payments", "Payments & cash drawer", "Sales",
                    "Money received by method and currency, change given, refunds paid out", "REPORTS_SALES", true),
            new Report.Info("discounts", "Discounts & price changes", "Sales",
                    "Every order discount, item discount and price override", "REPORTS_SALES", true),
            new Report.Info("returns-by-item", "Returns by item", "Sales", "What came back, how much was refunded and why", "REPORTS_SALES", true),
            new Report.Info("inventory-valuation", "Stock valuation", "Inventory",
                    "Stock on hand now at cost and at selling price", "REPORTS_INVENTORY", false),
            new Report.Info("stock-movements", "Stock movements", "Inventory",
                    "Stock in and out by kind (sales, purchases, adjustments, transfers...)", "REPORTS_INVENTORY", true),
            new Report.Info("purchases-by-vendor", "Purchases by vendor", "Purchasing",
                    "Ordered, received, invoiced and paid per vendor", "REPORTS_PURCHASING", true),
            new Report.Info("payables-aging", "Payables aging", "Purchasing",
                    "What is owed to each vendor, by how overdue it is", "REPORTS_PURCHASING", false));

    private static final String SOLD = ReportService.SOLD;
    /** Bucket size for grouping timestamps before mapping them to the store's local time. */
    private static final int BUCKET_SECONDS = 900;

    private final JdbcClient jdbc;
    private final SettingsService settings;
    private final CurrencyService currencies;

    public ReportsService(JdbcClient jdbc, SettingsService settings, CurrencyService currencies) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.currencies = currencies;
    }

    /** Filters common to all reports. Dates are store-local and inclusive. */
    public record Filters(LocalDate from, LocalDate to, Long branchId, String groupBy, String type, Long categoryId) {
    }

    record Scope(LocalDate from, LocalDate to, Instant start, Instant end, Long branchId, ZoneId zone) {
        Map<String, Object> params() {
            Map<String, Object> m = new HashMap<>();
            m.put("start", Timestamp.from(start));
            m.put("end", Timestamp.from(end));
            m.put("branch", branchId);
            m.put("fromDate", from);
            m.put("toDate", to);
            return m;
        }
    }

    Scope scope(LocalDate from, LocalDate to, Long branchId) {
        ZoneId zone = settings.current().timezone();
        if (from.isAfter(to)) {
            throw ApiException.badRequest("The start date is after the end date");
        }
        if (from.plusYears(2).isBefore(to)) {
            throw ApiException.badRequest("Pick a range of at most two years");
        }
        return new Scope(from, to, from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant(), branchId, zone);
    }

    @Transactional(readOnly = true)
    public Report run(String key, Filters f) {
        Scope s = scope(f.from(), f.to(), f.branchId());
        return switch (key) {
            case "sales-summary" -> salesSummary(s, f.groupBy() == null ? "day" : f.groupBy());
            case "sales-by-item" -> salesByItem(s, f.type(), f.categoryId());
            case "sales-by-category" -> salesByCategory(s);
            case "sales-by-time" -> salesByTime(s, "weekday".equals(f.groupBy()) ? "weekday" : "hour");
            case "sales-by-cashier" -> salesByCashier(s);
            case "services-by-staff" -> servicesByStaff(s);
            case "sales-by-customer" -> salesByCustomer(s);
            case "sales-by-branch" -> salesByBranch(s);
            case "payments" -> payments(s);
            case "discounts" -> discounts(s);
            case "returns-by-item" -> returnsByItem(s);
            case "inventory-valuation" -> inventoryValuation(s, f.categoryId());
            case "stock-movements" -> stockMovements(s);
            case "purchases-by-vendor" -> purchasesByVendor(s);
            case "payables-aging" -> payablesAging(s);
            default -> throw ApiException.notFound("Report");
        };
    }

    public static Report.Info info(String key) {
        return CATALOG.stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow(() -> ApiException.notFound("Report"));
    }

    // ------------------------------------------------------------------------------- summary

    /** One period of the sales summary. Returns count in the period they were made. */
    public record Period(String key, long orders, long items, BigDecimal gross, BigDecimal discounts, BigDecimal netSales,
                         BigDecimal tax, BigDecimal total, long returns, BigDecimal returnsTotal, BigDecimal returnsNet,
                         BigDecimal returnsTax, BigDecimal cost, BigDecimal returnedCost) {

        public BigDecimal netAfterReturns() {
            return netSales.subtract(returnsNet);
        }

        /** What customers paid, tax included, minus what was refunded. */
        public BigDecimal salesAfterReturns() {
            return total.subtract(returnsTotal);
        }

        public BigDecimal costAfterReturns() {
            return cost.subtract(returnedCost);
        }

        public BigDecimal profit() {
            return netAfterReturns().subtract(costAfterReturns());
        }

        public BigDecimal margin() {
            return pct(profit(), netAfterReturns());
        }

        static Period empty(String key) {
            return new Period(key, 0, 0, z(), z(), z(), z(), z(), 0, z(), z(), z(), z(), z());
        }

        Period plus(Period o) {
            return new Period(key, orders + o.orders, items + o.items, gross.add(o.gross), discounts.add(o.discounts),
                    netSales.add(o.netSales), tax.add(o.tax), total.add(o.total), returns + o.returns,
                    returnsTotal.add(o.returnsTotal), returnsNet.add(o.returnsNet), returnsTax.add(o.returnsTax),
                    cost.add(o.cost), returnedCost.add(o.returnedCost));
        }
    }

    /**
     * Sales and returns grouped by {@code keyOf} (applied to each 15-minute bucket's local time),
     * in key order. Shared by the summary report and the dashboard.
     */
    public TreeMap<String, Period> periods(Scope s, Function<ZonedDateTime, String> keyOf) {
        TreeMap<String, Period> out = new TreeMap<>();
        jdbc.sql("""
                        SELECT FLOOR(UNIX_TIMESTAMP(o.completed_at) / %d) AS b, COUNT(*) AS orders,
                               SUM(o.subtotal) AS gross, SUM(o.discount_amount + o.item_discount) AS disc,
                               SUM(o.total - o.tax_amount) AS net, SUM(o.tax_amount) AS tax, SUM(o.total) AS total,
                               SUM((SELECT COALESCE(SUM(i.quantity), 0) FROM order_items i WHERE i.order_id = o.id)) AS items,
                               SUM((SELECT COALESCE(SUM(i.cost_total), 0) FROM order_items i WHERE i.order_id = o.id)) AS cost
                        FROM orders o
                        WHERE o.status IN (%s) AND o.completed_at >= :start AND o.completed_at < :end
                          AND (:branch IS NULL OR o.branch_id = :branch)
                        GROUP BY b
                        """.formatted(BUCKET_SECONDS, SOLD))
                .params(s.params())
                .query((rs, n) -> {
                    String k = keyOf.apply(local(rs.getLong("b"), s.zone()));
                    Period p = new Period(k, rs.getLong("orders"), rs.getLong("items"), rs.getBigDecimal("gross"), rs.getBigDecimal("disc"),
                            rs.getBigDecimal("net"), rs.getBigDecimal("tax"), rs.getBigDecimal("total"), 0, z(), z(), z(),
                            rs.getBigDecimal("cost"), z());
                    out.merge(k, p, Period::plus);
                    return null;
                })
                .list();
        jdbc.sql("""
                        SELECT FLOOR(UNIX_TIMESTAMP(r.created_at) / %d) AS b, COUNT(*) AS n, SUM(r.total) AS total,
                               SUM(r.net) AS net, SUM(r.tax) AS tax,
                               SUM((SELECT COALESCE(SUM(i.cost_total * l.quantity / i.quantity), 0)
                                    FROM order_return_lines l JOIN order_items i ON i.id = l.order_item_id
                                    WHERE l.return_id = r.id)) AS cost
                        FROM order_returns r JOIN orders o ON o.id = r.order_id
                        WHERE r.created_at >= :start AND r.created_at < :end AND (:branch IS NULL OR o.branch_id = :branch)
                        GROUP BY b
                        """.formatted(BUCKET_SECONDS))
                .params(s.params())
                .query((rs, n) -> {
                    String k = keyOf.apply(local(rs.getLong("b"), s.zone()));
                    Period p = new Period(k, 0, 0, z(), z(), z(), z(), z(), rs.getLong("n"), rs.getBigDecimal("total"),
                            rs.getBigDecimal("net"), rs.getBigDecimal("tax"), z(), money(rs.getBigDecimal("cost")));
                    out.merge(k, p, Period::plus);
                    return null;
                })
                .list();
        return out;
    }

    private Report salesSummary(Scope s, String groupBy) {
        Function<ZonedDateTime, String> keyOf = switch (groupBy) {
            case "week" -> t -> t.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
            case "month" -> t -> t.toLocalDate().withDayOfMonth(1).toString();
            default -> t -> t.toLocalDate().toString();
        };
        TreeMap<String, Period> periods = periods(s, keyOf);
        // Every period in the range, also the empty ones.
        LocalDate d = LocalDate.parse(keyOf.apply(s.from().atStartOfDay(s.zone())));
        while (!d.isAfter(s.to())) {
            periods.putIfAbsent(d.toString(), Period.empty(d.toString()));
            d = switch (groupBy) {
                case "week" -> d.plusWeeks(1);
                case "month" -> d.plusMonths(1);
                default -> d.plusDays(1);
            };
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        Period total = Period.empty("total");
        for (Period p : periods.values()) {
            rows.add(periodRow(p, label(p.key(), groupBy)));
            total = total.plus(p);
        }
        return new Report("sales-summary", "Sales summary", "By " + groupBy, List.of(
                Column.text("period", groupBy.equals("week") ? "Week of" : groupBy.equals("month") ? "Month" : "Day"),
                Column.integer("orders", "Orders"), Column.integer("items", "Units"),
                Column.money("gross", "Gross (list prices)"), Column.money("discounts", "Discounts"),
                Column.money("netSales", "Net sales"), Column.money("tax", "Tax"), Column.money("total", "Total paid"),
                Column.money("returns", "Refunded"), Column.money("netAfterReturns", "Net after returns"),
                Column.money("cost", "Cost"), Column.money("profit", "Gross profit"), Column.percent("margin", "Margin")),
                rows, periodRow(total, "Total"), List.of(
                        "Sales count on the day they were paid; refunds on the day they were made.",
                        "Net sales are without tax, after discounts. Cost is what each line cost when it was sold (orders before cost "
                                + "tracking are estimated from catalog costs).",
                        "Gross profit = net sales after returns - cost of what was kept."), base());
    }

    private static Map<String, Object> periodRow(Period p, String label) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("period", label);
        r.put("orders", p.orders());
        r.put("items", p.items());
        r.put("gross", p.gross());
        r.put("discounts", p.discounts());
        r.put("netSales", p.netSales());
        r.put("tax", p.tax());
        r.put("total", p.total());
        r.put("returns", p.returnsTotal());
        r.put("netAfterReturns", p.netAfterReturns());
        r.put("cost", p.costAfterReturns());
        r.put("profit", p.profit());
        r.put("margin", p.margin());
        return r;
    }

    private static String label(String key, String groupBy) {
        LocalDate d = LocalDate.parse(key);
        return switch (groupBy) {
            case "month" -> d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.getYear();
            default -> key;
        };
    }

    private Report salesByTime(Scope s, String groupBy) {
        boolean hour = groupBy.equals("hour");
        TreeMap<String, Period> periods = periods(s, t -> hour ? String.format("%02d", t.getHour()) : String.valueOf(t.getDayOfWeek().getValue()));
        long days = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(s.from(), s.to()) + 1);
        List<Map<String, Object>> rows = new ArrayList<>();
        Period total = Period.empty("total");
        for (int i = hour ? 0 : 1; i <= (hour ? 23 : 7); i++) {
            String k = hour ? String.format("%02d", i) : String.valueOf(i);
            Period p = periods.getOrDefault(k, Period.empty(k));
            total = total.plus(p);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("slot", hour ? String.format("%02d:00–%02d:00", i, (i + 1) % 24) : DayOfWeek.of(i).getDisplayName(TextStyle.FULL, Locale.ENGLISH));
            r.put("orders", p.orders());
            r.put("total", p.total());
            r.put("average", p.orders() == 0 ? z() : div(p.total(), BigDecimal.valueOf(p.orders())));
            r.put("perDay", hour ? div(p.total(), BigDecimal.valueOf(days)) : null);
            r.put("share", null);
            rows.add(r);
        }
        BigDecimal all = total.total();
        rows.forEach(r -> r.put("share", pct((BigDecimal) r.get("total"), all)));
        if (!hour) {
            rows.forEach(r -> r.remove("perDay"));
        }
        List<Column> cols = new ArrayList<>(List.of(Column.text("slot", hour ? "Hour" : "Weekday"), Column.integer("orders", "Orders"),
                Column.money("total", "Sales (tax incl.)"), Column.money("average", "Average order")));
        if (hour) {
            cols.add(Column.money("perDay", "Per day"));
        }
        cols.add(Column.percent("share", "Share"));
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("slot", "Total");
        totals.put("orders", total.orders());
        totals.put("total", all);
        totals.put("average", total.orders() == 0 ? z() : div(all, BigDecimal.valueOf(total.orders())));
        return new Report("sales-by-time", "Sales by " + (hour ? "hour" : "weekday"), null, cols, rows, totals,
                List.of("Orders by the time they were paid, in the store's time zone. Refunds are not taken off here."), base());
    }

    // ------------------------------------------------------------------------- per dimension

    /** Net sales of a line after its returns (without tax), and its cost after returns. */
    private static final String LINE_NET = "(i.line_net - (i.refunded_amount - i.refunded_tax))";
    private static final String LINE_COST = "(i.cost_total * (i.quantity - i.refunded_qty) / i.quantity)";
    private static final String SOLD_WHERE = """
            o.status IN (%s) AND o.completed_at >= :start AND o.completed_at < :end AND (:branch IS NULL OR o.branch_id = :branch)
            """.formatted(SOLD);

    private Report salesByItem(Scope s, String type, Long categoryId) {
        Map<String, Object> params = s.params();
        params.put("type", type == null || type.isBlank() ? null : type);
        params.put("category", categoryId);
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT i.offering_id AS id, MAX(i.name) AS name, MAX(i.sku) AS sku, MAX(i.offering_type) AS type,
                               COALESCE(MAX(c.name), 'Uncategorised') AS category, SUM(i.quantity) AS sold,
                               SUM(i.refunded_qty) AS returned, SUM(i.item_discount) AS item_disc,
                               SUM(%s) AS net, SUM(%s) AS cost
                        FROM order_items i JOIN orders o ON o.id = i.order_id
                        LEFT JOIN offerings f ON f.id = i.offering_id LEFT JOIN categories c ON c.id = f.category_id
                        WHERE %s AND (:type IS NULL OR i.offering_type = :type) AND (:category IS NULL OR f.category_id = :category)
                        GROUP BY i.offering_id
                        ORDER BY net DESC
                        """.formatted(LINE_NET, LINE_COST, SOLD_WHERE))
                .params(params)
                .query((rs, n) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("name", rs.getString("name"));
                    r.put("sku", rs.getString("sku"));
                    r.put("type", typeLabel(rs.getString("type")));
                    r.put("category", rs.getString("category"));
                    r.put("sold", rs.getLong("sold"));
                    r.put("returned", rs.getLong("returned"));
                    r.put("netQty", rs.getLong("sold") - rs.getLong("returned"));
                    profitColumns(r, rs.getBigDecimal("net"), rs.getBigDecimal("cost"));
                    return r;
                })
                .list();
        return new Report("sales-by-item", "Sales by item", null, List.of(Column.text("name", "Item"), Column.text("sku", "SKU"),
                Column.text("type", "Type"), Column.text("category", "Category"), Column.integer("sold", "Sold"),
                Column.integer("returned", "Returned"), Column.integer("netQty", "Kept"), Column.money("netSales", "Net sales"),
                Column.money("cost", "Cost"), Column.money("profit", "Gross profit"), Column.percent("margin", "Margin"),
                Column.percent("share", "Share of sales")),
                withShare(rows, "netSales"), sumTotals(rows, "name", List.of("sold", "returned", "netQty", "netSales", "cost", "profit"), "netSales"),
                List.of("Sales in the period, less what was later returned from them. Net sales are without tax, after item and order discounts.",
                        "Packages count as one line each; services count jobs (hourly services: one per job)."), base());
    }

    private Report salesByCategory(Scope s) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT COALESCE(c.name, 'Uncategorised') AS category, COUNT(DISTINCT i.order_id) AS orders,
                               SUM(i.quantity - i.refunded_qty) AS qty, SUM(%s) AS net, SUM(%s) AS cost
                        FROM order_items i JOIN orders o ON o.id = i.order_id
                        LEFT JOIN offerings f ON f.id = i.offering_id LEFT JOIN categories c ON c.id = f.category_id
                        WHERE %s
                        GROUP BY c.id, c.name
                        ORDER BY net DESC
                        """.formatted(LINE_NET, LINE_COST, SOLD_WHERE))
                .params(s.params())
                .query((rs, n) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("category", rs.getString("category"));
                    r.put("orders", rs.getLong("orders"));
                    r.put("qty", rs.getLong("qty"));
                    profitColumns(r, rs.getBigDecimal("net"), rs.getBigDecimal("cost"));
                    return r;
                })
                .list();
        return new Report("sales-by-category", "Sales by category", null, List.of(Column.text("category", "Category"),
                Column.integer("orders", "Orders"), Column.integer("qty", "Units kept"), Column.money("netSales", "Net sales"),
                Column.money("cost", "Cost"), Column.money("profit", "Gross profit"), Column.percent("margin", "Margin"),
                Column.percent("share", "Share of sales")),
                withShare(rows, "netSales"), sumTotals(rows, "category", List.of("qty", "netSales", "cost", "profit"), "netSales"),
                List.of("By each entry's current category. Net sales without tax, after discounts and returns."), base());
    }

    private Report salesByCashier(Scope s) {
        Map<Long, long[]> returnsBy = new HashMap<>();
        Map<Long, BigDecimal> returnedBy = new HashMap<>();
        jdbc.sql("""
                        SELECT r.created_by AS u, COUNT(*) AS n, SUM(r.total) AS total FROM order_returns r JOIN orders o ON o.id = r.order_id
                        WHERE r.created_at >= :start AND r.created_at < :end AND (:branch IS NULL OR o.branch_id = :branch)
                        GROUP BY r.created_by
                        """)
                .params(s.params())
                .query((rs, n) -> {
                    returnsBy.put(rs.getLong("u"), new long[] {rs.getLong("n")});
                    returnedBy.put(rs.getLong("u"), rs.getBigDecimal("total"));
                    return null;
                })
                .list();
        Map<Long, Map<String, Object>> byUser = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT u.id, u.full_name, COUNT(*) AS orders, SUM(o.total) AS total, SUM(o.total - o.tax_amount) AS net,
                               SUM(o.discount_amount + o.item_discount) AS disc,
                               SUM((SELECT COUNT(*) FROM order_items i WHERE i.order_id = o.id AND (i.price_overridden OR i.item_discount > 0))) AS changed
                        FROM orders o JOIN users u ON u.id = o.cashier_id
                        WHERE %s
                        GROUP BY u.id, u.full_name
                        ORDER BY net DESC
                        """.formatted(SOLD_WHERE))
                .params(s.params())
                .query((rs, n) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    long orders = rs.getLong("orders");
                    r.put("cashier", rs.getString("full_name"));
                    r.put("orders", orders);
                    r.put("total", rs.getBigDecimal("total"));
                    r.put("netSales", rs.getBigDecimal("net"));
                    r.put("average", div(rs.getBigDecimal("total"), BigDecimal.valueOf(Math.max(orders, 1))));
                    r.put("discounts", rs.getBigDecimal("disc"));
                    r.put("changedLines", rs.getLong("changed"));
                    r.put("returns", 0L);
                    r.put("refunded", z());
                    byUser.put(rs.getLong("id"), r);
                    return null;
                })
                .list();
        // People who only processed returns still get a row.
        for (var e : returnsBy.entrySet()) {
            Map<String, Object> r = byUser.computeIfAbsent(e.getKey(), id -> {
                Map<String, Object> blank = new LinkedHashMap<>();
                blank.put("cashier", jdbc.sql("SELECT full_name FROM users WHERE id = :id").param("id", id).query(String.class).single());
                blank.put("orders", 0L);
                blank.put("total", z());
                blank.put("netSales", z());
                blank.put("average", z());
                blank.put("discounts", z());
                blank.put("changedLines", 0L);
                return blank;
            });
            r.put("returns", e.getValue()[0]);
            r.put("refunded", returnedBy.get(e.getKey()));
        }
        List<Map<String, Object>> rows = new ArrayList<>(byUser.values());
        return new Report("sales-by-cashier", "Sales by cashier", null, List.of(Column.text("cashier", "Cashier"),
                Column.integer("orders", "Orders"), Column.money("total", "Sales (tax incl.)"), Column.money("netSales", "Net sales"),
                Column.money("average", "Average order"), Column.money("discounts", "Discounts given"),
                Column.integer("changedLines", "Lines discounted / repriced"), Column.integer("returns", "Returns processed"),
                Column.money("refunded", "Refunded")),
                rows, sumTotals(rows, "cashier", List.of("orders", "total", "netSales", "discounts", "changedLines", "returns", "refunded"), null),
                List.of("Sales by who rang them up (paid in the period); returns by who processed them (made in the period)."), base());
    }

    private Report servicesByStaff(Scope s) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT i.staff_name AS staff, SUM(i.quantity - i.refunded_qty) AS jobs,
                               SUM((i.quantity - i.refunded_qty) * COALESCE(i.hours, 0)) AS hours,
                               SUM(%s) AS net, SUM(%s) AS cost
                        FROM order_items i JOIN orders o ON o.id = i.order_id
                        WHERE %s AND i.staff_name IS NOT NULL
                        GROUP BY i.staff_name
                        ORDER BY net DESC
                        """.formatted(LINE_NET, LINE_COST, SOLD_WHERE))
                .params(s.params())
                .query((rs, n) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("staff", rs.getString("staff"));
                    r.put("jobs", rs.getLong("jobs"));
                    r.put("hours", rs.getBigDecimal("hours").setScale(2, RoundingMode.HALF_UP));
                    profitColumns(r, rs.getBigDecimal("net"), rs.getBigDecimal("cost"));
                    return r;
                })
                .list();
        return new Report("services-by-staff", "Services by staff", null, List.of(Column.text("staff", "Done by"),
                Column.integer("jobs", "Jobs"), Column.decimal("hours", "Hours (hourly work)"), Column.money("netSales", "Revenue"),
                Column.money("cost", "Labour & parts cost"), Column.money("profit", "Gross profit"), Column.percent("margin", "Margin"),
                Column.percent("share", "Share")),
                withShare(rows, "netSales"), sumTotals(rows, "staff", List.of("jobs", "hours", "netSales", "cost", "profit"), "netSales"),
                List.of("Services, and packages that include a service, by the staff member recorded on the line; after returns."), base());
    }

    private Report salesByCustomer(Scope s) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT c.code, c.name, c.phone, c.is_default, COUNT(*) AS orders,
                               SUM(o.total - o.refunded_total) AS spent,
                               SUM(o.total - o.tax_amount - (o.refunded_total - o.refunded_tax)) AS net,
                               MIN(o.completed_at) AS first_at, MAX(o.completed_at) AS last_at
                        FROM orders o JOIN customers c ON c.id = o.customer_id
                        WHERE %s
                        GROUP BY c.id, c.code, c.name, c.phone, c.is_default
                        ORDER BY spent DESC
                        """.formatted(SOLD_WHERE))
                .params(s.params())
                .query((rs, n) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    long orders = rs.getLong("orders");
                    r.put("customer", rs.getString("name") + (rs.getBoolean("is_default") ? " (all walk-in sales)" : ""));
                    r.put("code", rs.getString("code"));
                    r.put("phone", rs.getString("phone"));
                    r.put("orders", orders);
                    r.put("spent", rs.getBigDecimal("spent"));
                    r.put("netSales", rs.getBigDecimal("net"));
                    r.put("average", div(rs.getBigDecimal("spent"), BigDecimal.valueOf(Math.max(orders, 1))));
                    r.put("first", rs.getTimestamp("first_at").toInstant());
                    r.put("last", rs.getTimestamp("last_at").toInstant());
                    return r;
                })
                .list();
        return new Report("sales-by-customer", "Sales by customer", null, List.of(Column.text("customer", "Customer"),
                Column.text("code", "Code"), Column.text("phone", "Phone"), Column.integer("orders", "Orders"),
                Column.money("spent", "Spent (tax incl.)"), Column.money("netSales", "Net sales"), Column.money("average", "Average order"),
                Column.datetime("first", "First in period"), Column.datetime("last", "Last in period"), Column.percent("share", "Share")),
                withShare(rows, "spent"), sumTotals(rows, "customer", List.of("orders", "spent", "netSales"), "spent"),
                List.of("After returns from those sales."), base());
    }

    private Report salesByBranch(Scope s) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT b.name, COUNT(*) AS orders, SUM(o.total) AS total, SUM(o.refunded_total) AS refunded,
                               SUM(o.total - o.tax_amount - (o.refunded_total - o.refunded_tax)) AS net,
                               SUM(o.tax_amount - o.refunded_tax) AS tax,
                               SUM((SELECT COALESCE(SUM(%s), 0) FROM order_items i WHERE i.order_id = o.id)) AS cost
                        FROM orders o JOIN branches b ON b.id = o.branch_id
                        WHERE %s
                        GROUP BY b.id, b.name
                        ORDER BY net DESC
                        """.formatted(LINE_COST, SOLD_WHERE))
                .params(s.params())
                .query((rs, n) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("branch", rs.getString("name"));
                    r.put("orders", rs.getLong("orders"));
                    r.put("total", rs.getBigDecimal("total"));
                    r.put("refunded", rs.getBigDecimal("refunded"));
                    r.put("tax", rs.getBigDecimal("tax"));
                    profitColumns(r, rs.getBigDecimal("net"), rs.getBigDecimal("cost"));
                    return r;
                })
                .list();
        return new Report("sales-by-branch", "Sales by branch", null, List.of(Column.text("branch", "Branch"),
                Column.integer("orders", "Orders"), Column.money("total", "Sales (tax incl.)"), Column.money("refunded", "Refunded"),
                Column.money("tax", "Tax kept"), Column.money("netSales", "Net sales"), Column.money("cost", "Cost"),
                Column.money("profit", "Gross profit"), Column.percent("margin", "Margin"), Column.percent("share", "Share")),
                withShare(rows, "netSales"), sumTotals(rows, "branch", List.of("orders", "total", "refunded", "tax", "netSales", "cost", "profit"), "netSales"),
                List.of("Sales paid in the period, less what was later refunded from them."), base());
    }

    // ------------------------------------------------------------------------------ payments

    private Report payments(Scope s) {
        String baseCode = currencies.base().code();
        List<Map<String, Object>> rows = new ArrayList<>();
        // KHQR (and any other non-cash method): what the code charged, in its currency.
        jdbc.sql("""
                        SELECT p.method, COALESCE(p.currency, :baseCode) AS cur, COUNT(*) AS n, SUM(COALESCE(p.currency_amount, p.amount)) AS received,
                               SUM(p.amount) AS base
                        FROM payments p JOIN orders o ON o.id = p.order_id
                        WHERE p.method <> 'CASH' AND p.status IN ('PAID', 'REFUNDED') AND p.paid_at >= :start AND p.paid_at < :end
                          AND (:branch IS NULL OR o.branch_id = :branch)
                        GROUP BY p.method, cur
                        """)
                .params(with(s.params(), "baseCode", baseCode))
                .query((rs, n) -> {
                    rows.add(paymentRow(rs.getString("method"), rs.getString("cur"), rs.getLong("n"), rs.getBigDecimal("received"),
                            z(), rs.getBigDecimal("base")));
                    return null;
                })
                .list();
        // Cash per currency: handed over, change given back, kept.
        Map<String, Object[]> cash = new TreeMap<>();
        jdbc.sql("""
                        SELECT t.currency_code AS cur, COUNT(DISTINCT p.id) AS n, SUM(t.amount) AS received, SUM(t.base_amount) AS base
                        FROM payment_tenders t JOIN payments p ON p.id = t.payment_id JOIN orders o ON o.id = p.order_id
                        WHERE p.status IN ('PAID', 'REFUNDED') AND p.paid_at >= :start AND p.paid_at < :end AND (:branch IS NULL OR o.branch_id = :branch)
                        GROUP BY t.currency_code
                        """)
                .params(s.params())
                .query((rs, n) -> cash.put(rs.getString("cur"), new Object[] {rs.getLong("n"), rs.getBigDecimal("received"), z(), rs.getBigDecimal("base")}))
                .list();
        jdbc.sql("""
                        SELECT p.change_currency AS cur, SUM(p.change_amount) AS change_amt, SUM(p.change_due) AS base
                        FROM payments p JOIN orders o ON o.id = p.order_id
                        WHERE p.method = 'CASH' AND p.change_amount > 0 AND p.status IN ('PAID', 'REFUNDED')
                          AND p.paid_at >= :start AND p.paid_at < :end AND (:branch IS NULL OR o.branch_id = :branch)
                        GROUP BY p.change_currency
                        """)
                .params(s.params())
                .query((rs, n) -> {
                    Object[] c = cash.computeIfAbsent(rs.getString("cur"), k -> new Object[] {0L, z(), z(), z()});
                    c[2] = rs.getBigDecimal("change_amt");
                    c[3] = ((BigDecimal) c[3]).subtract(rs.getBigDecimal("base"));
                    return null;
                })
                .list();
        cash.forEach((cur, c) -> rows.add(paymentRow("CASH", cur, (Long) c[0], (BigDecimal) c[1], (BigDecimal) c[2], (BigDecimal) c[3])));
        BigDecimal refunds = jdbc.sql("""
                        SELECT COALESCE(SUM(r.total), 0) FROM order_returns r JOIN orders o ON o.id = r.order_id
                        WHERE r.created_at >= :start AND r.created_at < :end AND (:branch IS NULL OR o.branch_id = :branch)
                        """)
                .params(s.params()).query(BigDecimal.class).single();
        if (refunds.signum() > 0) {
            rows.add(paymentRow("REFUNDS", baseCode, 0, refunds.negate(), z(), refunds.negate()));
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("method", "Total, in " + baseCode);
        totals.put("base", rows.stream().map(r -> (BigDecimal) r.get("base")).reduce(z(), BigDecimal::add));
        return new Report("payments", "Payments & cash drawer", null, List.of(Column.text("method", "Method"),
                Column.text("currency", "Currency"), Column.integer("payments", "Payments"), Column.decimal("received", "Received"),
                Column.decimal("change", "Change given"), Column.decimal("kept", "Kept"), Column.money("base", "Value in " + baseCode)),
                rows, totals, List.of(
                        "Money taken by when it was paid (later refunds don't remove it); refunds are a separate line, by when they were made.",
                        "Cash amounts are in their own currency: count each drawer currency against \"Kept\". Refunds are recorded in "
                                + baseCode + "; the app doesn't track which currency they were paid back in."), base());
    }

    private static Map<String, Object> paymentRow(String method, String currency, long count, BigDecimal received, BigDecimal change,
                                                  BigDecimal base) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("method", switch (method) {
            case "CASH" -> "Cash";
            case "KHQR" -> "KHQR";
            case "REFUNDS" -> "Refunds paid out";
            default -> method;
        });
        r.put("currency", currency);
        r.put("payments", count);
        r.put("received", received.stripTrailingZeros());
        r.put("change", change.stripTrailingZeros());
        r.put("kept", received.subtract(change).stripTrailingZeros());
        r.put("base", money(base));
        return r;
    }

    private Report discounts(Scope s) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT * FROM (
                            SELECT o.completed_at AS at, o.order_number, u.full_name AS cashier, 'Order discount' AS kind, NULL AS item,
                                   CASE WHEN o.discount_type = 'PERCENT' THEN CONCAT(CAST(o.discount_value AS DOUBLE), '%%') ELSE 'Amount' END AS detail,
                                   o.discount_amount AS amount
                            FROM orders o JOIN users u ON u.id = o.cashier_id WHERE %1$s AND o.discount_amount > 0
                            UNION ALL
                            SELECT o.completed_at, o.order_number, u.full_name, 'Item discount', i.name,
                                   CASE WHEN i.item_discount_type = 'PERCENT' THEN CONCAT(CAST(i.item_discount_value AS DOUBLE), '%%') ELSE 'Amount' END,
                                   i.item_discount
                            FROM order_items i JOIN orders o ON o.id = i.order_id JOIN users u ON u.id = o.cashier_id
                            WHERE %1$s AND i.item_discount > 0
                            UNION ALL
                            SELECT o.completed_at, o.order_number, COALESCE(b.full_name, u.full_name), 'Price override', i.name,
                                   CONCAT(i.list_price, ' → ', i.unit_price, ': ', COALESCE(i.price_override_reason, '')),
                                   ROUND((i.list_price - i.unit_price) * i.quantity * COALESCE(i.hours, 1), 2)
                            FROM order_items i JOIN orders o ON o.id = i.order_id JOIN users u ON u.id = o.cashier_id
                            LEFT JOIN users b ON b.id = i.price_override_by
                            WHERE %1$s AND i.price_overridden
                        ) x ORDER BY at DESC
                        """.formatted(SOLD_WHERE.replace("\n", " ")))
                .params(s.params())
                .query((rs, n) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("at", rs.getTimestamp("at").toInstant());
                    r.put("order", rs.getString("order_number"));
                    r.put("cashier", rs.getString("cashier"));
                    r.put("kind", rs.getString("kind"));
                    r.put("item", rs.getString("item"));
                    r.put("detail", rs.getString("detail"));
                    r.put("amount", rs.getBigDecimal("amount"));
                    return r;
                })
                .list();
        Map<String, Object> totals = sumTotals(rows, "at", List.of("amount"), null);
        totals.put("at", null);
        totals.put("order", rows.size() + " entries");
        return new Report("discounts", "Discounts & price changes", null, List.of(Column.datetime("at", "When"),
                Column.text("order", "Order"), Column.text("cashier", "By"), Column.text("kind", "Kind"), Column.text("item", "Item"),
                Column.text("detail", "Detail"), Column.money("amount", "Money off")),
                rows, totals, List.of("Price overrides show the catalog price, the price charged and the reason; a negative amount means "
                        + "the price was raised. Amounts include tax as entered (VAT-included prices)."), base());
    }

    private Report returnsByItem(Scope s) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT MAX(i.name) AS name, MAX(i.sku) AS sku, COUNT(DISTINCT r.id) AS returns, SUM(l.quantity) AS qty,
                               SUM(l.total) AS refunded, SUM(l.tax) AS tax,
                               GROUP_CONCAT(DISTINCT r.reason ORDER BY r.reason SEPARATOR '; ') AS reasons
                        FROM order_return_lines l JOIN order_returns r ON r.id = l.return_id
                        JOIN order_items i ON i.id = l.order_item_id JOIN orders o ON o.id = r.order_id
                        WHERE r.created_at >= :start AND r.created_at < :end AND (:branch IS NULL OR o.branch_id = :branch)
                        GROUP BY i.offering_id
                        ORDER BY refunded DESC
                        """)
                .params(s.params())
                .query((rs, n) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("name", rs.getString("name"));
                    r.put("sku", rs.getString("sku"));
                    r.put("returns", rs.getLong("returns"));
                    r.put("qty", rs.getLong("qty"));
                    r.put("refunded", rs.getBigDecimal("refunded"));
                    r.put("tax", rs.getBigDecimal("tax"));
                    r.put("reasons", rs.getString("reasons"));
                    return r;
                })
                .list();
        return new Report("returns-by-item", "Returns by item", null, List.of(Column.text("name", "Item"), Column.text("sku", "SKU"),
                Column.integer("returns", "Returns"), Column.integer("qty", "Units returned"), Column.money("refunded", "Refunded"),
                Column.money("tax", "Tax refunded"), Column.text("reasons", "Reasons")),
                rows, sumTotals(rows, "name", List.of("qty", "refunded", "tax"), null),
                List.of("By when the return was made. Every return is listed under Sales > Returns & refunds."), base());
    }

    // ----------------------------------------------------------------------------- inventory

    private Report inventoryValuation(Scope s, Long categoryId) {
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
                .params(with(s.params(), "category", categoryId))
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
                    r.put("value", money(cost.multiply(BigDecimal.valueOf(qty))));
                    r.put("price", price);
                    r.put("retail", money(price.multiply(BigDecimal.valueOf(qty))));
                    r.put("margin", pct(price.subtract(cost), price));
                    r.put("low", qty <= rs.getInt("threshold") ? "Low" : "");
                    return r;
                })
                .list();
        return new Report("inventory-valuation", "Stock valuation", "As of now", List.of(Column.text("name", "Item"),
                Column.text("sku", "SKU"), Column.text("category", "Category"), Column.integer("qty", "On hand"), Column.text("unit", "Unit"),
                Column.money("cost", "Cost each"), Column.money("value", "Value at cost"), Column.money("price", "Price each"),
                Column.money("retail", "Value at price"), Column.percent("margin", "Margin"), Column.text("low", "Stock")),
                withShare(rows, "value"), sumTotals(rows, "name", List.of("qty", "value", "retail"), null),
                List.of("Stock in active storages right now, at each item's current cost and selling price (VAT-included prices include VAT)."), base());
    }

    private Report stockMovements(Scope s) {
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
                    r.put("value", money(rs.getBigDecimal("value")));
                    return r;
                })
                .list();
        return new Report("stock-movements", "Stock movements", null, List.of(Column.text("type", "Kind"), Column.integer("movements", "Lines"),
                Column.integer("in", "Units in"), Column.integer("out", "Units out"), Column.integer("net", "Net"),
                Column.money("value", "Value change (at current cost)")),
                rows, sumTotals(rows, "type", List.of("movements", "in", "out", "net", "value"), null),
                List.of("Base units. Transfers within the selected scope show as both out and in. Values use today's cost."), base());
    }

    // ---------------------------------------------------------------------------- purchasing

    private Report purchasesByVendor(Scope s) {
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
                    r.put("received", money(rs.getBigDecimal("received")));
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
                rows, sumTotals(rows, "vendor", List.of("placed", "placedValue", "received", "invoiced", "inputTax", "paid", "owed"), null),
                List.of("Ordered by when orders were placed, received by delivery date, invoiced by invoice date, paid by payment date. "
                        + "Received value is at the order's cost, before tax. \"Owed now\" is today's unpaid balance."), base());
    }

    private Report payablesAging(Scope s) {
        LocalDate today = LocalDate.now(s.zone());
        Map<String, Map<String, Object>> byVendor = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT v.name, v.code, i.total - i.paid AS bal, DATEDIFF(:today, i.due_date) AS late
                        FROM vendor_invoices i JOIN vendors v ON v.id = i.vendor_id LEFT JOIN purchase_orders p ON p.id = i.purchase_order_id
                        WHERE i.status IN ('UNPAID', 'PARTIALLY_PAID') AND (:branch IS NULL OR p.branch_id = :branch)
                        ORDER BY v.name
                        """)
                .params(with(s.params(), "today", today))
                .query((rs, n) -> {
                    Map<String, Object> r = byVendor.computeIfAbsent(rs.getString("code"), k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("vendor", rsName(rs));
                        m.put("invoices", 0L);
                        for (String b : List.of("current", "d30", "d60", "d90", "over90", "total")) {
                            m.put(b, z());
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
                rows, sumTotals(rows, "vendor", List.of("invoices", "current", "d30", "d60", "d90", "over90", "total"), null),
                List.of("Unpaid and partly paid vendor invoices by how far past their due date they are, today."), base());
    }

    private static String rsName(java.sql.ResultSet rs) {
        try {
            return rs.getString("name");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // -------------------------------------------------------------------------------- helpers

    private static void profitColumns(Map<String, Object> r, BigDecimal net, BigDecimal cost) {
        BigDecimal n = money(net);
        BigDecimal c = money(cost);
        r.put("netSales", n);
        r.put("cost", c);
        r.put("profit", n.subtract(c));
        r.put("margin", pct(n.subtract(c), n));
    }

    /** Adds each row's share of the column's total, in %. */
    private static List<Map<String, Object>> withShare(List<Map<String, Object>> rows, String column) {
        BigDecimal total = rows.stream().map(r -> (BigDecimal) r.get(column)).reduce(z(), BigDecimal::add);
        rows.forEach(r -> r.put("share", pct((BigDecimal) r.get(column), total)));
        return rows;
    }

    /** A totals row: the label in {@code labelKey}, sums of {@code sums}, and margin when there is profit. */
    private static Map<String, Object> sumTotals(List<Map<String, Object>> rows, String labelKey, List<String> sums, String shareOf) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put(labelKey, "Total");
        for (String k : sums) {
            boolean decimal = rows.stream().anyMatch(r -> r.get(k) instanceof BigDecimal);
            if (decimal) {
                t.put(k, rows.stream().map(r -> r.get(k) == null ? z() : (BigDecimal) r.get(k)).reduce(z(), BigDecimal::add));
            } else {
                t.put(k, rows.stream().mapToLong(r -> r.get(k) == null ? 0 : ((Number) r.get(k)).longValue()).sum());
            }
        }
        if (t.get("profit") instanceof BigDecimal p && t.get("netSales") instanceof BigDecimal n) {
            t.put("margin", pct(p, n));
        }
        if (shareOf != null) {
            t.put("share", rows.isEmpty() ? null : BigDecimal.valueOf(100));
        }
        return t;
    }

    private String base() {
        return currencies.base().code();
    }

    private static ZonedDateTime local(long bucket, ZoneId zone) {
        return Instant.ofEpochSecond(bucket * BUCKET_SECONDS).atZone(zone);
    }

    static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0 || part == null) {
            return null;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal div(BigDecimal a, BigDecimal b) {
        return b.signum() == 0 ? z() : a.divide(b, 2, RoundingMode.HALF_UP);
    }

    static BigDecimal money(BigDecimal v) {
        return v == null ? z() : v.setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal z() {
        return BigDecimal.ZERO.setScale(2);
    }

    private static Map<String, Object> with(Map<String, Object> m, String k, Object v) {
        m.put(k, v);
        return m;
    }

    private static String typeLabel(String t) {
        return switch (t) {
            case "PRODUCT" -> "Product";
            case "SERVICE" -> "Service";
            case "ITEM" -> "Item";
            case "PACKAGE" -> "Package";
            default -> t;
        };
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
