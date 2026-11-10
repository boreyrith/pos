package com.pos.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.pos.accounting.AccountType;
import com.pos.accounting.AccountType.Section;
import com.pos.report.Report.Column;

/**
 * The financial reports, read from the journal: profit and loss, balance sheet, trial balance,
 * and other income and expenses. Dates are the entries' dates (store-local days).
 * <p>Balances are shown on each account's normal side, so a positive figure is the usual case
 * (money held, money owed, income earned, cost incurred).
 */
@Component
class AccountingReports {

    private final JdbcClient jdbc;

    AccountingReports(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One account with its debits and credits over some dates. */
    record Row(Long id, String code, String name, AccountType type, BigDecimal debit, BigDecimal credit) {

        /** On the account's normal side. */
        BigDecimal balance() {
            return type.section().debitNormal() ? debit.subtract(credit) : credit.subtract(debit);
        }
    }

    /** Every account, with its debits and credits for entries dated from..to (from null: since the start). */
    private List<Row> rows(LocalDate from, LocalDate to, Long branchId) {
        Map<String, Object> p = new HashMap<>();
        p.put("from", from);
        p.put("to", to);
        p.put("branch", branchId);
        return jdbc.sql("""
                        SELECT a.id, a.code, a.name, a.type, COALESCE(s.dr, 0) AS dr, COALESCE(s.cr, 0) AS cr
                        FROM accounts a LEFT JOIN (
                            SELECT l.account_id, SUM(l.debit) AS dr, SUM(l.credit) AS cr
                            FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                            WHERE (:from IS NULL OR e.entry_date >= :from) AND e.entry_date <= :to
                              AND (:branch IS NULL OR e.branch_id = :branch)
                            GROUP BY l.account_id
                        ) s ON s.account_id = a.id
                        ORDER BY a.code
                        """).params(p)
                .query((rs, i) -> new Row(rs.getLong("id"), rs.getString("code"), rs.getString("name"), AccountType.valueOf(rs.getString("type")),
                        rs.getBigDecimal("dr"), rs.getBigDecimal("cr")))
                .list();
    }

    private static Map<String, Object> row(String code, String account, BigDecimal amount) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", code);
        r.put("account", account);
        r.put("amount", amount);
        return r;
    }

    private static boolean moved(Row r) {
        return r.debit().signum() != 0 || r.credit().signum() != 0;
    }

    // ---------------------------------------------------------------------------- profit and loss

    Report profitAndLoss(ReportsService.Scope s, String currency) {
        List<Row> all = rows(s.from(), s.to(), s.branchId());
        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal revenue = section(out, all, AccountType.REVENUE, "Revenue", "Total revenue");
        BigDecimal cost = section(out, all, AccountType.COST_OF_SALES, "Cost of sales", "Total cost of sales");
        BigDecimal gross = revenue.subtract(cost);
        out.add(row(null, "Gross profit", gross));
        BigDecimal expenses = section(out, all, AccountType.EXPENSE, "Operating expenses", "Total operating expenses");
        BigDecimal operating = gross.subtract(expenses);
        out.add(row(null, "Operating profit", operating));
        BigDecimal otherIncome = section(out, all, AccountType.OTHER_INCOME, "Other income", "Total other income");
        BigDecimal otherExpenses = section(out, all, AccountType.OTHER_EXPENSE, "Other expenses", "Total other expenses");
        BigDecimal net = operating.add(otherIncome).subtract(otherExpenses);
        for (Map<String, Object> r : out) {
            BigDecimal amount = (BigDecimal) r.get("amount");
            r.put("share", amount == null || revenue.signum() == 0 ? null : ReportsService.pct(amount, revenue));
        }
        Map<String, Object> totals = row(null, "Net profit", net);
        totals.put("share", revenue.signum() == 0 ? null : ReportsService.pct(net, revenue));
        return new Report("profit-and-loss", "Profit and loss", s.from() + " to " + s.to(),
                List.of(Column.text("code", "Code"), Column.text("account", "Account"), Column.money("amount", "Amount"),
                        Column.percent("share", "% of revenue")),
                out, totals,
                List.of("From the journal: sales after discounts and returns, cost of goods sold at catalog cost, stock adjustments, "
                                + "expenses and other income and expenses recorded in Accounting, and vendor invoices without a purchase order.",
                        "Amounts are without VAT. Only activity from the day the books were started is included."),
                currency);
    }

    /** Adds the section's heading, accounts that moved and subtotal; returns the subtotal. */
    private static BigDecimal section(List<Map<String, Object>> out, List<Row> all, AccountType type, String heading, String totalLabel) {
        List<Row> rows = all.stream().filter(r -> r.type() == type && moved(r)).toList();
        BigDecimal total = rows.stream().map(Row::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (rows.isEmpty()) {
            return total;
        }
        out.add(row(null, heading, null));
        rows.forEach(r -> out.add(row(r.code(), r.name(), r.balance())));
        out.add(row(null, totalLabel, total));
        return total;
    }

    // ------------------------------------------------------------------------------ balance sheet

    Report balanceSheet(ReportsService.Scope s, String currency) {
        List<Row> all = rows(null, s.to(), s.branchId());
        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal assets = BigDecimal.ZERO;
        out.add(row(null, "Assets", null));
        for (AccountType t : List.of(AccountType.CASH, AccountType.BANK, AccountType.CURRENT_ASSET, AccountType.FIXED_ASSET)) {
            for (Row r : all) {
                if (r.type() == t && moved(r)) {
                    out.add(row(r.code(), r.name(), r.balance()));
                    assets = assets.add(r.balance());
                }
            }
        }
        out.add(row(null, "Total assets", assets));
        BigDecimal liabilities = BigDecimal.ZERO;
        out.add(row(null, "Liabilities", null));
        for (Row r : all) {
            if (r.type().section() == Section.LIABILITY && moved(r)) {
                out.add(row(r.code(), r.name(), r.balance()));
                liabilities = liabilities.add(r.balance());
            }
        }
        out.add(row(null, "Total liabilities", liabilities));
        BigDecimal equity = BigDecimal.ZERO;
        out.add(row(null, "Equity", null));
        for (Row r : all) {
            if (r.type().section() == Section.EQUITY && moved(r)) {
                out.add(row(r.code(), r.name(), r.balance()));
                equity = equity.add(r.balance());
            }
        }
        // Profit not yet closed into retained earnings: everything earned less everything spent, to date.
        BigDecimal profit = BigDecimal.ZERO;
        for (Row r : all) {
            if (r.type().section() == Section.INCOME) {
                profit = profit.add(r.balance());
            } else if (r.type().section() == Section.EXPENSE) {
                profit = profit.subtract(r.balance());
            }
        }
        out.add(row(null, "Profit to date", profit));
        equity = equity.add(profit);
        out.add(row(null, "Total equity", equity));
        BigDecimal difference = assets.subtract(liabilities).subtract(equity);
        List<String> notes = new ArrayList<>(List.of("As of the end date. Every entry since the books were started is included.",
                "Profit to date is all income less all costs and expenses, until it is closed into retained earnings with a journal entry."));
        if (s.branchId() != null) {
            notes.add("For one branch: stock, payables and amounts not posted to a branch (cost changes, opening balances) are left out, "
                    + "so it need not balance. Choose all branches for the company's balance sheet.");
        } else if (difference.signum() != 0) {
            notes.add("Assets differ from liabilities and equity by " + difference.toPlainString() + ".");
        }
        return new Report("balance-sheet", "Balance sheet", "As of " + s.to(),
                List.of(Column.text("code", "Code"), Column.text("account", "Account"), Column.money("amount", "Amount")),
                out, row(null, "Total liabilities and equity", liabilities.add(equity)), notes, currency);
    }

    // ------------------------------------------------------------------------------ trial balance

    Report trialBalance(ReportsService.Scope s, String currency) {
        List<Row> before = rows(null, s.from().minusDays(1), s.branchId());
        List<Row> period = rows(s.from(), s.to(), s.branchId());
        Map<Long, Row> openings = new HashMap<>();
        before.forEach(r -> openings.put(r.id(), r));
        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal[] t = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (Row r : period) {
            Row o = openings.get(r.id());
            BigDecimal net = o.debit().subtract(o.credit()).add(r.debit()).subtract(r.credit());
            if (!moved(r) && !moved(o)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r.code());
            m.put("account", r.name());
            m.put("type", r.type().label());
            m.put("opening", o.debit().subtract(o.credit()));
            m.put("debit", r.debit());
            m.put("credit", r.credit());
            m.put("closingDebit", net.signum() > 0 ? net : BigDecimal.ZERO);
            m.put("closingCredit", net.signum() < 0 ? net.negate() : BigDecimal.ZERO);
            out.add(m);
            t[0] = t[0].add(r.debit());
            t[1] = t[1].add(r.credit());
            t[2] = t[2].add((BigDecimal) m.get("closingDebit"));
            t[3] = t[3].add((BigDecimal) m.get("closingCredit"));
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("code", "Total");
        totals.put("debit", t[0]);
        totals.put("credit", t[1]);
        totals.put("closingDebit", t[2]);
        totals.put("closingCredit", t[3]);
        return new Report("trial-balance", "Trial balance", s.from() + " to " + s.to(),
                List.of(Column.text("code", "Code"), Column.text("account", "Account"), Column.text("type", "Type"),
                        Column.money("opening", "Opening (debit - credit)"), Column.money("debit", "Debits"), Column.money("credit", "Credits"),
                        Column.money("closingDebit", "Closing debit"), Column.money("closingCredit", "Closing credit")),
                out, totals,
                List.of("Debits equal credits in every entry, so the totals of each pair of columns are equal.",
                        "Opening and closing balances include every entry since the books were started."),
                currency);
    }

    // ------------------------------------------------------------------ other income and expenses

    Report otherIncomeExpenses(ReportsService.Scope s, String currency) {
        Map<String, Object> p = new HashMap<>();
        p.put("from", s.from());
        p.put("to", s.to());
        p.put("branch", s.branchId());
        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal[] t = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        jdbc.sql("""
                        SELECT t.txn_date, t.txn_number, t.kind, a.code, a.name AS account, m.name AS money, b.name AS branch,
                               COALESCE(t.party, v.name, c.name) AS party, t.reference, t.note, t.amount, t.tax
                        FROM other_transactions t JOIN accounts a ON a.id = t.account_id JOIN accounts m ON m.id = t.money_account_id
                        JOIN branches b ON b.id = t.branch_id
                        LEFT JOIN vendors v ON v.id = t.vendor_id LEFT JOIN customers c ON c.id = t.customer_id
                        WHERE t.status = 'POSTED' AND t.txn_date BETWEEN :from AND :to AND (:branch IS NULL OR t.branch_id = :branch)
                        ORDER BY t.txn_date, t.id
                        """).params(p)
                .query((rs, i) -> {
                    boolean income = "INCOME".equals(rs.getString("kind"));
                    BigDecimal amount = rs.getBigDecimal("amount");
                    BigDecimal tax = rs.getBigDecimal("tax");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("date", rs.getDate("txn_date").toLocalDate().toString());
                    m.put("number", rs.getString("txn_number"));
                    m.put("kind", income ? "Income" : "Expense");
                    m.put("account", rs.getString("code") + " " + rs.getString("account"));
                    m.put("party", rs.getString("party"));
                    m.put("money", rs.getString("money"));
                    m.put("branch", rs.getString("branch"));
                    m.put("reference", rs.getString("reference"));
                    m.put("note", rs.getString("note"));
                    m.put("income", income ? amount.subtract(tax) : null);
                    m.put("expense", income ? null : amount.subtract(tax));
                    m.put("tax", tax);
                    m.put("amount", income ? amount : amount.negate());
                    t[0] = t[0].add(income ? amount.subtract(tax) : BigDecimal.ZERO);
                    t[1] = t[1].add(income ? BigDecimal.ZERO : amount.subtract(tax));
                    t[2] = t[2].add(income ? amount : amount.negate());
                    return out.add(m);
                }).list();
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("date", "Total");
        totals.put("income", t[0]);
        totals.put("expense", t[1]);
        totals.put("amount", t[2]);
        return new Report("other-income-expenses", "Other income and expenses", s.from() + " to " + s.to(),
                List.of(Column.text("date", "Date"), Column.text("number", "Number"), Column.text("kind", "Kind"), Column.text("account", "Account"),
                        Column.text("party", "Paid by / to"), Column.text("money", "Cash or bank"), Column.text("branch", "Branch"),
                        Column.text("reference", "Reference"), Column.text("note", "Note"), Column.money("income", "Income"),
                        Column.money("expense", "Expense"), Column.money("tax", "VAT"), Column.money("amount", "Money in / out")),
                out, totals,
                List.of("Income and expense are without VAT; money in / out is what was received (+) or paid (-), VAT included.",
                        "Voided ones are left out."),
                currency);
    }
}
