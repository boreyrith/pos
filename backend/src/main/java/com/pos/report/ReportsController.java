package com.pos.report;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.access.Permission;
import com.pos.auth.AuthUser;
import com.pos.common.ApiException;
import com.pos.inventory.BranchContext;
import com.pos.settings.SettingsService;

/**
 * The Reports module: /reports/catalog lists the reports this person may open; /reports/run/{key}
 * runs one (JSON) and /reports/run/{key}.csv exports it. Each report needs its own permission
 * (Sales, Inventory or Purchasing reports). Branch rules as everywhere: without "All branches",
 * only the person's own branch.
 */
@RestController
public class ReportsController {

    private final ReportsService reports;
    private final BranchContext branches;
    private final SettingsService settings;

    public ReportsController(ReportsService reports, BranchContext branches, SettingsService settings) {
        this.reports = reports;
        this.branches = branches;
        this.settings = settings;
    }

    @GetMapping("/reports/catalog")
    public List<Report.Info> catalog(@AuthenticationPrincipal AuthUser user) {
        return ReportsService.CATALOG.stream().filter(i -> user.has(Permission.valueOf(i.permission()))).toList();
    }

    @GetMapping("/reports/run/{key}")
    public Report run(@PathVariable String key, @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                      @RequestParam(required = false) Long branchId, @RequestParam(required = false) String groupBy,
                      @RequestParam(required = false) String type, @RequestParam(required = false) Long categoryId,
                      @AuthenticationPrincipal AuthUser user) {
        return report(key, from, to, branchId, groupBy, type, categoryId, user);
    }

    @GetMapping(value = "/reports/run/{key}.csv", produces = "text/csv")
    public ResponseEntity<String> csv(@PathVariable String key, @RequestParam(required = false) LocalDate from,
                                      @RequestParam(required = false) LocalDate to, @RequestParam(required = false) Long branchId,
                                      @RequestParam(required = false) String groupBy, @RequestParam(required = false) String type,
                                      @RequestParam(required = false) Long categoryId, @AuthenticationPrincipal AuthUser user) {
        Report r = report(key, from, to, branchId, groupBy, type, categoryId, user);
        ZoneId zone = settings.current().timezone();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone);
        StringBuilder out = new StringBuilder();
        out.append(esc(r.title())).append('\n');
        out.append(esc(range(from, to, zone, ReportsService.info(key).dated()))).append(",Currency ").append(r.currency()).append('\n').append('\n');
        out.append(String.join(",", r.columns().stream().map(c -> esc(c.label())).toList())).append('\n');
        for (Map<String, Object> row : r.rows()) {
            out.append(line(r.columns(), row, fmt)).append('\n');
        }
        if (r.totals() != null && !r.totals().isEmpty()) {
            out.append(line(r.columns(), r.totals(), fmt)).append('\n');
        }
        out.append('\n');
        for (String note : r.notes()) {
            out.append(esc(note)).append('\n');
        }
        String name = key + "-" + (from == null ? LocalDate.now(zone) : from) + (to == null ? "" : "-to-" + to) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                // A byte-order mark so Excel reads Khmer text and symbols as UTF-8.
                .body("﻿" + out);
    }

    private Report report(String key, LocalDate from, LocalDate to, Long branchId, String groupBy, String type, Long categoryId,
                          AuthUser user) {
        Report.Info info = ReportsService.info(key);
        if (!user.has(Permission.valueOf(info.permission()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Your role can't open the " + info.title() + " report");
        }
        LocalDate today = LocalDate.now(settings.current().timezone());
        LocalDate start = from != null ? from : today.withDayOfMonth(1);
        LocalDate end = to != null ? to : today;
        return reports.run(key, new ReportsService.Filters(start, end, branches.viewScope(user, branchId), groupBy, type, categoryId));
    }

    private static String range(LocalDate from, LocalDate to, ZoneId zone, boolean dated) {
        LocalDate today = LocalDate.now(zone);
        return dated ? (from == null ? today.withDayOfMonth(1) : from) + " to " + (to == null ? today : to) : "As of " + today;
    }

    private static String line(List<Report.Column> columns, Map<String, Object> row, DateTimeFormatter fmt) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            Object v = row.get(columns.get(i).key());
            if (v == null) {
                continue;
            }
            b.append(v instanceof Instant t ? fmt.format(t) : v instanceof BigDecimal d ? d.toPlainString() : esc(String.valueOf(v)));
        }
        return b.toString();
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        // Neutralise spreadsheet formulas in text cells.
        String t = s.matches("^[=+\\-@].*") && !s.matches("^-?\\d.*") ? "'" + s : s;
        return t.contains(",") || t.contains("\"") || t.contains("\n") ? "\"" + t.replace("\"", "\"\"") + "\"" : t;
    }
}
