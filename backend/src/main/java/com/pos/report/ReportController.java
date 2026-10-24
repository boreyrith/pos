package com.pos.report;

import com.pos.report.TaxReportService.TaxReport;
import com.pos.common.ApiException;
import com.pos.settings.SettingsService;
import com.pos.tax.TaxMode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.AuthUser;
import com.pos.inventory.BranchContext;
import com.pos.report.ReportDtos.Dashboard;

@RestController
@RequestMapping("/reports")
@PreAuthorize("hasAnyAuthority('REPORTS_DASHBOARD','REPORTS_TAX')")
public class ReportController {

    private final ReportService service;
    private final TaxReportService taxes;
    private final SettingsService settings;
    private final BranchContext branches;

    public ReportController(ReportService service, TaxReportService taxes, SettingsService settings, BranchContext branches) {
        this.service = service;
        this.taxes = taxes;
        this.settings = settings;
        this.branches = branches;
    }

    /**
     * The last {@code days} days (1 = today) or {@code from}..{@code to}; people without "All
     * branches" always see their own branch.
     */
    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('REPORTS_DASHBOARD')")
    public Dashboard dashboard(@RequestParam(defaultValue = "7") int days, @RequestParam(required = false) LocalDate from,
                               @RequestParam(required = false) LocalDate to, @RequestParam(required = false) Long branchId,
                               @AuthenticationPrincipal AuthUser user) {
        LocalDate today = LocalDate.now(settings.current().timezone());
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(Math.clamp(days, 1, 366) - 1L);
        if (start.isAfter(end) || start.plusDays(366).isBefore(end)) {
            throw ApiException.badRequest("Pick a range of up to a year");
        }
        return service.dashboard(start, end, branches.viewScope(user, branchId), user.has(com.pos.access.Permission.PURCHASE_VIEW));
    }

    /** Tax collected and refunded between two store-local dates (both included); this month by default. */
    @GetMapping("/tax")
    @PreAuthorize("hasAuthority('REPORTS_TAX')")
    public TaxReport tax(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                         @RequestParam(required = false) Long branchId, @AuthenticationPrincipal AuthUser user) {
        requireTaxEnabled();
        LocalDate today = LocalDate.now(settings.current().timezone());
        return taxes.report(from == null ? today.withDayOfMonth(1) : from, to == null ? today : to,
                branches.viewScope(user, branchId));
    }

    /** The tax report as CSV: the summary, per rate, then every sale and return in the range. */
    @GetMapping(value = "/tax.csv", produces = "text/csv")
    @PreAuthorize("hasAuthority('REPORTS_TAX')")
    public ResponseEntity<String> taxCsv(@RequestParam LocalDate from, @RequestParam LocalDate to,
                                         @RequestParam(required = false) Long branchId,
                                         @AuthenticationPrincipal AuthUser user) {
        requireTaxEnabled();
        Long scope = branches.viewScope(user, branchId);
        TaxReport report = taxes.report(from, to, scope);
        ZoneId zone = settings.current().timezone();
        var s = report.summary();
        StringBuilder csv = new StringBuilder();
        csv.append("Tax report,").append(from).append(',').append(to).append(',').append(report.currency()).append('\n');
        csv.append("\nSummary,Amount\n");
        row(csv, "Orders", s.orders());
        row(csv, "Gross sales", s.grossSales());
        row(csv, "Discounts", s.discounts());
        row(csv, "Net sales (without tax)", s.netSales());
        row(csv, "Taxable sales", s.taxableSales());
        row(csv, "Non-taxable sales", s.nonTaxableSales());
        row(csv, "Tax added on top", s.taxAdded());
        row(csv, "Tax included in prices", s.taxIncluded());
        row(csv, "Tax collected", s.taxCollected());
        row(csv, "Returns", s.returns());
        row(csv, "Refunded", s.refunds());
        row(csv, "Tax refunded", s.taxRefunded());
        row(csv, "Net tax due", s.netTax());
        csv.append("\nRate,Percent,Included,Taxable base,Tax,Refunded base,Tax refunded,Net tax\n");
        for (var r : report.byRate()) {
            csv.append(esc(r.name())).append(',').append(r.rate().movePointRight(2).stripTrailingZeros().toPlainString())
                    .append(',').append(r.included() ? "yes" : "no").append(',').append(r.taxableBase()).append(',')
                    .append(r.tax()).append(',').append(r.refundedBase()).append(',').append(r.refundedTax()).append(',')
                    .append(r.netTax()).append('\n');
        }
        csv.append("\nDate,Type,Document,Order,Tax mode,Net,Tax added,Tax included,Total\n");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone);
        for (var e : taxes.entries(from, to, scope)) {
            csv.append(fmt.format(e.at())).append(',').append(e.type()).append(',').append(esc(e.document())).append(',')
                    .append(esc(e.orderNumber())).append(',').append(e.mode()).append(',').append(e.net()).append(',')
                    .append(e.taxAdded()).append(',').append(e.taxIncluded()).append(',').append(e.total()).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tax-report-" + from + "-to-" + to + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString());
    }

    /** The tax report is turned off while the store is set to No VAT. */
    private void requireTaxEnabled() {
        if (settings.current().tax().mode() == TaxMode.NONE) {
            throw ApiException.conflict("The tax report is off while the store is set to No VAT");
        }
    }

    private static void row(StringBuilder csv, String label, Object value) {
        csv.append(label).append(',').append(value).append('\n');
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
