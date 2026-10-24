package com.pos.report;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.pos.common.ApiException;
import com.pos.currency.CurrencyService;
import com.pos.settings.SettingsService;
import com.pos.tax.TaxMode;

/**
 * The tax report for a date range: tax collected on sales (added on top, or included in prices),
 * taxable and non-taxable sales, tax given back by returns, and the net tax due, overall and per
 * rate. Every figure comes from what each order and return saved at the time, so changing the
 * tax settings never changes a past period. Sales count by payment date, returns by return date.
 */
@Service
public class TaxReportService {

    /**
     * @param grossSales      what customers paid (tax included)
     * @param netSales        grossSales without tax
     * @param taxAdded        tax charged on top of prices (VAT setup value)
     * @param taxIncluded     tax inside prices (VAT included)
     * @param taxableSales    net sales that were charged tax
     * @param nonTaxableSales net sales charged no tax (exempt, out of scope, No VAT)
     * @param refunds         refunded to customers (tax included)
     * @param taxRefunded     the tax inside those refunds
     * @param netTax          tax collected minus tax refunded: what is due
     */
    public record Summary(long orders, long returns, BigDecimal grossSales, BigDecimal discounts, BigDecimal netSales,
                          BigDecimal taxAdded, BigDecimal taxIncluded, BigDecimal taxCollected, BigDecimal taxableSales,
                          BigDecimal nonTaxableSales, BigDecimal refunds, BigDecimal taxRefunded, BigDecimal netRefunds,
                          BigDecimal netTax) {
    }

    /** One tax rate: net sales and tax charged under it, and what returns gave back. */
    public record RateRow(String name, BigDecimal rate, boolean included, BigDecimal taxableBase, BigDecimal tax,
                          BigDecimal refundedBase, BigDecimal refundedTax, BigDecimal netTax) {
    }

    public record ModeRow(TaxMode mode, long orders, BigDecimal grossSales, BigDecimal tax) {
    }

    public record DayRow(LocalDate date, BigDecimal grossSales, BigDecimal taxCollected, BigDecimal refunds,
                         BigDecimal taxRefunded) {
    }

    /** A sale or a return, for the export. Amounts of returns are negative. */
    public record Entry(Instant at, String type, String document, String orderNumber, TaxMode mode, String label,
                        BigDecimal net, BigDecimal taxAdded, BigDecimal taxIncluded, BigDecimal total) {
    }

    public record TaxReport(String currency, LocalDate from, LocalDate to, Summary summary, List<RateRow> byRate,
                            List<ModeRow> byMode, List<DayRow> daily) {
    }

    private static final String SALES = """
            FROM orders o
            WHERE o.status IN (%s) AND o.completed_at >= :start AND o.completed_at < :end
              AND (:branch IS NULL OR o.branch_id = :branch)
            """.formatted(ReportService.SOLD);

    private static final String RETURNS = """
            FROM order_returns r JOIN orders o ON o.id = r.order_id
            WHERE r.created_at >= :start AND r.created_at < :end AND (:branch IS NULL OR o.branch_id = :branch)
            """;

    private final JdbcClient jdbc;
    private final SettingsService settings;
    private final CurrencyService currencies;
    private final ReportService reports;

    public TaxReportService(JdbcClient jdbc, SettingsService settings, CurrencyService currencies, ReportService reports) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.currencies = currencies;
        this.reports = reports;
    }

    /** {@code from} and {@code to} are store-local dates, both included; {@code branchId} null is every branch. */
    public TaxReport report(LocalDate from, LocalDate to, Long branchId) {
        Range r = range(from, to);
        return new TaxReport(currencies.base().code(), from, to, summary(r, branchId), byRate(r, branchId),
                byMode(r, branchId), daily(r, from, to, branchId));
    }

    public List<Entry> entries(LocalDate from, LocalDate to, Long branchId) {
        Range r = range(from, to);
        return jdbc.sql("""
                        SELECT * FROM (
                            SELECT o.completed_at AS at, 'SALE' AS type, o.order_number AS document, o.order_number,
                                   o.tax_mode, o.tax_label, o.total - o.tax_amount AS net, o.tax_amount - o.tax_included AS tax_added,
                                   o.tax_included, o.total
                            %s
                            UNION ALL
                            SELECT r.created_at, 'RETURN', r.return_number, o.order_number, o.tax_mode, o.tax_label, -r.net,
                                   IF(o.tax_mode = 'EXCLUSIVE', -r.tax, 0), IF(o.tax_mode = 'EXCLUSIVE', 0, -r.tax), -r.total
                            %s
                        ) x ORDER BY at, document
                        """.formatted(SALES, RETURNS))
                .params(r.params(branchId))
                .query((rs, n) -> new Entry(rs.getTimestamp("at").toInstant(), rs.getString("type"), rs.getString("document"),
                        rs.getString("order_number"), TaxMode.valueOf(rs.getString("tax_mode")), rs.getString("tax_label"),
                        rs.getBigDecimal("net"), rs.getBigDecimal("tax_added"), rs.getBigDecimal("tax_included"),
                        rs.getBigDecimal("total")))
                .list();
    }

    private Summary summary(Range r, Long branchId) {
        record Sales(long orders, BigDecimal gross, BigDecimal discounts, BigDecimal tax, BigDecimal included,
                     BigDecimal taxable, BigDecimal nonTaxable) {
        }
        record Refunds(long count, BigDecimal total, BigDecimal tax) {
        }
        Sales s = jdbc.sql("""
                        SELECT COUNT(*) AS orders, COALESCE(SUM(o.total), 0) AS gross, COALESCE(SUM(o.discount_amount + o.item_discount), 0) AS discounts,
                               COALESCE(SUM(o.tax_amount), 0) AS tax, COALESCE(SUM(o.tax_included), 0) AS included,
                               COALESCE(SUM(o.taxable_amount), 0) AS taxable, COALESCE(SUM(o.non_taxable_amount), 0) AS non_taxable
                        """ + SALES)
                .params(r.params(branchId))
                .query((rs, n) -> new Sales(rs.getLong("orders"), rs.getBigDecimal("gross"), rs.getBigDecimal("discounts"),
                        rs.getBigDecimal("tax"), rs.getBigDecimal("included"), rs.getBigDecimal("taxable"),
                        rs.getBigDecimal("non_taxable")))
                .single();
        Refunds f = jdbc.sql("SELECT COUNT(*) AS cnt, COALESCE(SUM(r.total), 0) AS total, COALESCE(SUM(r.tax), 0) AS tax " + RETURNS)
                .params(r.params(branchId))
                .query((rs, n) -> new Refunds(rs.getLong("cnt"), rs.getBigDecimal("total"), rs.getBigDecimal("tax")))
                .single();
        return new Summary(s.orders(), f.count(), s.gross(), s.discounts(), s.gross().subtract(s.tax()),
                s.tax().subtract(s.included()), s.included(), s.tax(), s.taxable(), s.nonTaxable(), f.total(), f.tax(),
                f.total().subtract(f.tax()), s.tax().subtract(f.tax()));
    }

    private List<RateRow> byRate(Range r, Long branchId) {
        return jdbc.sql("""
                        SELECT name, rate, included, SUM(base) AS base, SUM(tax) AS tax,
                               SUM(r_base) AS r_base, SUM(r_tax) AS r_tax
                        FROM (
                            SELECT t.name, t.rate, t.included, t.base, t.tax, 0 AS r_base, 0 AS r_tax
                            FROM order_taxes t JOIN orders o ON o.id = t.order_id
                            WHERE o.status IN (%s) AND o.completed_at >= :start AND o.completed_at < :end
                              AND (:branch IS NULL OR o.branch_id = :branch)
                            UNION ALL
                            SELECT i.tax_name, i.tax_rate, i.price_includes_tax, 0, 0, l.total - l.tax, l.tax
                            FROM order_return_lines l JOIN order_returns r ON r.id = l.return_id
                            JOIN order_items i ON i.id = l.order_item_id JOIN orders o ON o.id = r.order_id
                            WHERE r.created_at >= :start AND r.created_at < :end AND (:branch IS NULL OR o.branch_id = :branch)
                        ) x
                        GROUP BY name, rate, included
                        ORDER BY rate DESC, name, included
                        """.formatted(ReportService.SOLD))
                .params(r.params(branchId))
                .query((rs, n) -> {
                    BigDecimal tax = rs.getBigDecimal("tax");
                    BigDecimal refundedTax = rs.getBigDecimal("r_tax");
                    return new RateRow(rs.getString("name"), rs.getBigDecimal("rate"), rs.getBoolean("included"),
                            rs.getBigDecimal("base"), tax, rs.getBigDecimal("r_base"), refundedTax, tax.subtract(refundedTax));
                })
                .list();
    }

    private List<ModeRow> byMode(Range r, Long branchId) {
        return jdbc.sql("SELECT o.tax_mode, COUNT(*) AS cnt, SUM(o.total) AS gross, SUM(o.tax_amount) AS tax " + SALES
                        + " GROUP BY o.tax_mode ORDER BY o.tax_mode")
                .params(r.params(branchId))
                .query((rs, n) -> new ModeRow(TaxMode.valueOf(rs.getString("tax_mode")), rs.getLong("cnt"),
                        rs.getBigDecimal("gross"), rs.getBigDecimal("tax")))
                .list();
    }

    private List<DayRow> daily(Range r, LocalDate from, LocalDate to, Long branchId) {
        var params = r.params(branchId);
        params.put("offset", reports.offset());
        var rows = jdbc.sql("""
                        SELECT DATE(CONVERT_TZ(at, '+00:00', :offset)) AS day, SUM(gross) AS gross, SUM(tax) AS tax,
                               SUM(refunds) AS refunds, SUM(r_tax) AS r_tax
                        FROM (
                            SELECT o.completed_at AS at, o.total AS gross, o.tax_amount AS tax, 0 AS refunds, 0 AS r_tax
                            %s
                            UNION ALL
                            SELECT r.created_at, 0, 0, r.total, r.tax
                            %s
                        ) x GROUP BY day
                        """.formatted(SALES, RETURNS))
                .params(params)
                .query((rs, n) -> new DayRow(rs.getObject("day", LocalDate.class), rs.getBigDecimal("gross"),
                        rs.getBigDecimal("tax"), rs.getBigDecimal("refunds"), rs.getBigDecimal("r_tax")))
                .list();
        var byDay = rows.stream().collect(java.util.stream.Collectors.toMap(DayRow::date, d -> d));
        return from.datesUntil(to.plusDays(1))
                .map(d -> byDay.getOrDefault(d, new DayRow(d, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)))
                .toList();
    }

    private record Range(Instant start, Instant end) {
        java.util.Map<String, Object> params(Long branchId) {
            var m = new java.util.HashMap<String, Object>();
            m.put("start", Timestamp.from(start));
            m.put("end", Timestamp.from(end));
            m.put("branch", branchId);
            return m;
        }
    }

    private Range range(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw ApiException.badRequest("The start date is after the end date");
        }
        if (from.plusDays(366).isBefore(to)) {
            throw ApiException.badRequest("Pick a range of at most a year");
        }
        ZoneId zone = settings.current().timezone();
        return new Range(from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant());
    }
}
