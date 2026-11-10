package com.pos.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Accounting end to end: starting the books from what Munita knows, every operation posting a
 * balanced entry (sales, KHQR, returns, deliveries, invoices, payments, stock adjustments, other
 * income and expenses), the Inventory account following the stock valuation, the reports adding
 * up, and who may do what.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountingIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Phnom_Penh"));

    @LocalServerPort
    int port;

    RestClient http;
    String admin;
    String manager;
    String accountant;
    String cashier;
    String stock;

    record Res(int status, JsonNode body) {
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api").build();
        admin = login("admin", "admin1234");
        manager = login("manager", "manager1234");
        accountant = login("accountant", "accountant1234");
        cashier = login("cashier", "cashier1234");
        stock = login("stock", "stock12345");
        startBooks();
    }

    /** Starts the books once: the suggested opening balances plus cash counted in the drawer. */
    private void startBooks() {
        if (get("/accounting/settings", accountant).body().get("started").asBoolean()) {
            return;
        }
        // Before the start, a sale posts nothing.
        sellCash(product(5), 1);
        assertThat(get("/accounting/journal?from=" + TODAY.minusYears(1) + "&to=" + TODAY, accountant).body().get("totalItems").asLong()).isZero();

        List<Map<String, Object>> lines = new ArrayList<>();
        for (JsonNode l : get("/accounting/setup/suggested", accountant).body()) {
            Map<String, Object> m = new HashMap<>();
            m.put("accountId", l.get("accountId").asLong());
            m.put("debit", l.get("debit").decimalValue());
            m.put("credit", l.get("credit").decimalValue());
            m.put("vendorId", l.get("vendorId").isNull() ? null : l.get("vendorId").asLong());
            lines.add(m);
        }
        lines.add(Map.of("accountId", account("1001"), "debit", new BigDecimal("150.00"), "credit", BigDecimal.ZERO));
        assertThat(send("POST", "/accounting/setup", Map.of("startDate", TODAY.toString(), "lines", lines), manager).status())
                .as("managers can't start the books").isEqualTo(403);
        Res started = send("POST", "/accounting/setup", Map.of("startDate", TODAY.toString(), "lines", lines), accountant);
        assertThat(started.status()).as(String.valueOf(started.body())).isEqualTo(200);
        assertThat(started.body().get("started").asBoolean()).isTrue();
        assertThat(send("POST", "/accounting/setup", Map.of("startDate", TODAY.toString(), "lines", List.of()), accountant).status())
                .isEqualTo(409);
        assertInventoryFollowsStock();
    }

    @Test
    void everyOperationPostsABalancedEntry() {
        JsonNode p = product(0);
        long pid = p.get("id").asLong();
        BigDecimal cost = get("/offerings/" + pid, admin).body().get("cost").decimalValue();

        // A cash sale: money into the branch's drawer, sales and VAT, stock at cost to cost of goods sold.
        JsonNode sale = sellCash(p, 3);
        JsonNode e = entryFor("SALE", sale.get("orderNumber").asString());
        assertThat(debit(e, "1001")).isEqualByComparingTo(dec(sale, "total"));
        assertThat(credit(e, "4000").add(credit(e, "2200"))).isEqualByComparingTo(dec(sale, "total"));
        assertThat(credit(e, "2200")).isEqualByComparingTo(dec(sale, "taxAmount"));
        assertThat(debit(e, "5000")).isEqualByComparingTo(cost.multiply(BigDecimal.valueOf(3)));
        assertThat(credit(e, "1200")).isEqualByComparingTo(cost.multiply(BigDecimal.valueOf(3)));
        assertInventoryFollowsStock();

        // A KHQR sale posts when the payment arrives, into Bakong.
        Res pending = send("POST", "/orders", Map.of("items", List.of(Map.of("offeringId", pid, "quantity", 1)), "paymentMethod", "KHQR"), cashier);
        assertThat(pending.status()).as(String.valueOf(pending.body())).isEqualTo(201);
        String khqrNumber = pending.body().get("orderNumber").asString();
        assertThat(journal("source=SALE&q=" + khqrNumber).get("totalItems").asLong()).isZero();
        send("POST", "/payments/" + pending.body().get("payments").get(0).get("id").asLong() + "/simulate-paid", null, cashier);
        assertThat(debit(entryFor("SALE", khqrNumber), "1100")).isEqualByComparingTo(dec(pending.body(), "total"));

        // A return: refund out of the drawer, sales and VAT back, stock back into Inventory.
        JsonNode item = sale.get("items").get(0);
        Res ret = send("POST", "/orders/" + sale.get("id").asLong() + "/returns",
                Map.of("lines", List.of(Map.of("orderItemId", item.get("id").asLong(), "quantity", 1))), admin);
        assertThat(ret.status()).as(String.valueOf(ret.body())).isEqualTo(200);
        JsonNode r = journal("source=SALE_RETURN&q=" + sale.get("orderNumber").asString()).get("items").get(0);
        assertThat(credit(r, "1001")).isEqualByComparingTo(debit(r, "4000").add(debit(r, "2200")));
        assertThat(debit(r, "1200")).isEqualByComparingTo(cost);
        assertInventoryFollowsStock();

        // A damaged item written off.
        long unit = get("/offerings/" + pid, admin).body().get("unitId").asLong();
        Res adj = send("POST", "/stock-adjustments", Map.of("storageId", sellingStorage(), "kind", "ADJUSTMENT", "lines",
                List.of(Map.of("offeringId", pid, "operation", "DECREASE", "reason", "DAMAGED",
                        "quantities", List.of(Map.of("unitId", unit, "quantity", 1))))), stock);
        assertThat(adj.status()).as(String.valueOf(adj.body())).isEqualTo(201);
        JsonNode a = entryFor("STOCK_ADJUSTMENT", adj.body().get("adjustmentNumber").asString());
        assertThat(debit(a, "5100")).isEqualByComparingTo(cost);
        assertInventoryFollowsStock();

        // Bought at a different price, received with the cost updated: the price difference and the
        // revaluation of what was on hand both post, and Inventory still matches the stock.
        JsonNode vendor = send("POST", "/vendors", Map.of("code", "A" + (System.nanoTime() % 100000), "name", "Books Vendor",
                "paymentTermsDays", 0), manager).body();
        BigDecimal newCost = cost.add(new BigDecimal("0.40"));
        long po = send("POST", "/purchase-orders", Map.of("vendorId", vendor.get("id").asLong(), "storageId", sellingStorage(),
                "lines", List.of(Map.of("offeringId", pid, "quantity", 10, "unitCost", newCost))), manager).body().get("id").asLong();
        send("POST", "/purchase-orders/" + po + "/place", null, manager);
        long line = get("/purchase-orders/" + po, manager).body().get("lines").get(0).get("id").asLong();
        Res received = send("POST", "/purchase-orders/" + po + "/receive",
                Map.of("lines", List.of(Map.of("lineId", line, "quantity", 10)), "updateCost", true), manager);
        assertThat(received.status()).as(String.valueOf(received.body())).isEqualTo(200);
        JsonNode grn = entryFor("GOODS_RECEIPT", received.body().get("receipts").get(0).get("receiptNumber").asString());
        BigDecimal owed = newCost.multiply(BigDecimal.TEN);
        assertThat(credit(grn, "2100")).isEqualByComparingTo(owed);
        assertThat(debit(grn, "1200")).isEqualByComparingTo(cost.multiply(BigDecimal.TEN));
        assertThat(debit(grn, "5200")).isEqualByComparingTo(owed.subtract(cost.multiply(BigDecimal.TEN)));
        assertThat(journal("source=REVALUATION").get("totalItems").asLong()).isPositive();
        assertInventoryFollowsStock();

        // The vendor's invoice clears goods not invoiced, with input VAT; paying it in cash empties what is owed.
        Res invoice = send("POST", "/vendor-invoices", Map.of("vendorId", vendor.get("id").asLong(), "purchaseOrderId", po,
                "invoiceNumber", "B-1", "invoiceDate", TODAY.toString(), "subtotal", owed, "tax", new BigDecimal("1.00")), accountant);
        assertThat(invoice.status()).as(String.valueOf(invoice.body())).isEqualTo(201);
        JsonNode inv = entryFor("VENDOR_INVOICE", "B-1");
        assertThat(debit(inv, "2100")).isEqualByComparingTo(owed);
        assertThat(debit(inv, "1300")).isEqualByComparingTo("1.00");
        assertThat(credit(inv, "2000")).isEqualByComparingTo(owed.add(BigDecimal.ONE));
        BigDecimal payablesBefore = dec(get("/accounting/overview", accountant).body(), "payables");
        Res paid = send("POST", "/vendor-invoices/" + invoice.body().get("id").asLong() + "/payments",
                Map.of("amount", owed.add(BigDecimal.ONE), "method", "CASH", "paidOn", TODAY.toString()), accountant);
        assertThat(paid.status()).as(String.valueOf(paid.body())).isEqualTo(200);
        assertThat(paid.body().get("payments").get(0).get("moneyAccount").asString()).startsWith("1001 ");
        assertThat(dec(get("/accounting/overview", accountant).body(), "payables"))
                .isEqualByComparingTo(payablesBefore.subtract(owed.add(BigDecimal.ONE)));

        // A bill without a purchase order goes to the expense account chosen; voiding it takes it back.
        long rent = account("6000");
        Res bill = send("POST", "/vendor-invoices", Map.of("vendorId", vendor.get("id").asLong(), "invoiceNumber", "RENT-11",
                "invoiceDate", TODAY.toString(), "subtotal", new BigDecimal("300.00"), "tax", BigDecimal.ZERO, "expenseAccountId", rent), accountant);
        assertThat(bill.body().get("expenseAccount").asString()).isEqualTo("6000 Rent");
        assertThat(debit(entryFor("VENDOR_INVOICE", "RENT-11"), "6000")).isEqualByComparingTo("300.00");
        send("POST", "/vendor-invoices/" + bill.body().get("id").asLong() + "/void", null, accountant);
        assertThat(credit(entryFor("VENDOR_INVOICE_VOID", "RENT-11"), "6000")).isEqualByComparingTo("300.00");
        // Only expense accounts fit a bill.
        assertThat(send("POST", "/vendor-invoices", Map.of("vendorId", vendor.get("id").asLong(), "invoiceNumber", "X-1",
                "invoiceDate", TODAY.toString(), "subtotal", BigDecimal.ONE, "tax", BigDecimal.ZERO, "expenseAccountId", account("4000")),
                accountant).status()).isEqualTo(400);

        // Other expenses and income, recorded by a manager.
        Res power = send("POST", "/accounting/other", Map.of("kind", "EXPENSE", "date", TODAY.toString(), "accountId", account("6200"),
                "moneyAccountId", account("1001"), "amount", new BigDecimal("11.00"), "tax", new BigDecimal("1.00"), "party", "EDC"), manager);
        assertThat(power.status()).as(String.valueOf(power.body())).isEqualTo(201);
        assertThat(power.body().get("number").asString()).startsWith("EXP-");
        JsonNode pe = get("/accounting/journal/" + power.body().get("entryId").asLong(), manager).body();
        assertThat(debit(pe, "6200")).isEqualByComparingTo("10.00");
        assertThat(debit(pe, "1300")).isEqualByComparingTo("1.00");
        assertThat(credit(pe, "1001")).isEqualByComparingTo("11.00");
        Res interest = send("POST", "/accounting/other", Map.of("kind", "INCOME", "date", TODAY.toString(), "accountId", account("4200"),
                "moneyAccountId", account("1110"), "amount", new BigDecimal("2.50")), manager);
        assertThat(interest.status()).isEqualTo(201);
        // An income can't go to an expense account, nor money through a non-money account.
        assertThat(send("POST", "/accounting/other", Map.of("kind", "INCOME", "date", TODAY.toString(), "accountId", account("6200"),
                "moneyAccountId", account("1110"), "amount", BigDecimal.ONE), manager).status()).isEqualTo(400);
        assertThat(send("POST", "/accounting/other", Map.of("kind", "EXPENSE", "date", TODAY.toString(), "accountId", account("6200"),
                "moneyAccountId", account("1200"), "amount", BigDecimal.ONE), manager).status()).isEqualTo(400);
        Res voided = send("POST", "/accounting/other/" + power.body().get("id").asLong() + "/void", Map.of("reason", "Entered twice"), manager);
        assertThat(voided.body().get("status").asString()).isEqualTo("VOID");
        assertThat(get("/accounting/journal/" + power.body().get("entryId").asLong(), manager).body().get("reversedByNumber").isNull()).isFalse();

        // The reports add up.
        String range = "?from=" + TODAY + "&to=" + TODAY;
        JsonNode tb = get("/reports/run/trial-balance" + range, accountant).body();
        assertThat(dec(tb.get("totals"), "debit")).isEqualByComparingTo(dec(tb.get("totals"), "credit"));
        assertThat(dec(tb.get("totals"), "closingDebit")).isEqualByComparingTo(dec(tb.get("totals"), "closingCredit"));
        JsonNode bs = get("/reports/run/balance-sheet" + range, accountant).body();
        BigDecimal assets = amountOf(bs, "Total assets");
        assertThat(dec(bs.get("totals"), "amount")).isEqualByComparingTo(assets);
        JsonNode pl = get("/reports/run/profit-and-loss" + range, accountant).body();
        assertThat(pl.toString()).contains("Interest income", "Cost of goods sold");
        assertThat(get("/reports/run/other-income-expenses" + range, manager).body().get("rows")).isNotEmpty();
        assertThat(get("/reports/run/profit-and-loss" + range, cashier).status()).isEqualTo(403);
        assertInventoryFollowsStock();
    }

    @Test
    void journalEntriesPeriodsAndPermissions() {
        // Cashiers and stock staff don't see the books; managers see and record, but don't post journals or change the chart.
        assertThat(get("/accounting/overview", cashier).status()).isEqualTo(403);
        assertThat(get("/accounting/overview", stock).status()).isEqualTo(403);
        assertThat(get("/accounting/overview", manager).status()).isEqualTo(200);
        Map<String, Object> entry = Map.of("date", TODAY.toString(), "memo", "Owner put money in", "lines", List.of(
                Map.of("accountId", account("1110"), "debit", new BigDecimal("500.00")),
                Map.of("accountId", account("3000"), "credit", new BigDecimal("500.00"))));
        assertThat(send("POST", "/accounting/journal", entry, manager).status()).isEqualTo(403);
        assertThat(send("POST", "/accounting/accounts", Map.of("code", "6950", "name", "Cleaning", "type", "EXPENSE"), manager).status())
                .isEqualTo(403);

        // Journals must balance; a balanced one posts and can be reversed once.
        Res unbalanced = send("POST", "/accounting/journal", Map.of("date", TODAY.toString(), "memo", "Oops", "lines", List.of(
                Map.of("accountId", account("1110"), "debit", new BigDecimal("500.00")),
                Map.of("accountId", account("3000"), "credit", new BigDecimal("499.00")))), accountant);
        assertThat(unbalanced.status()).isEqualTo(400);
        assertThat(unbalanced.body().get("detail").asString()).contains("doesn't balance");
        Res posted = send("POST", "/accounting/journal", entry, accountant);
        assertThat(posted.status()).as(String.valueOf(posted.body())).isEqualTo(201);
        long id = posted.body().get("id").asLong();
        assertThat(posted.body().get("entryNumber").asString()).startsWith("JE-");
        Res reversed = send("POST", "/accounting/journal/" + id + "/reverse", Map.of("reason", "Wrong account"), accountant);
        assertThat(reversed.status()).as(String.valueOf(reversed.body())).isEqualTo(200);
        assertThat(credit(reversed.body(), "1110")).isEqualByComparingTo("500.00");
        assertThat(send("POST", "/accounting/journal/" + id + "/reverse", Map.of("reason", "Again"), accountant).status()).isEqualTo(409);
        // Automatic entries are corrected through their documents.
        JsonNode sale = sellCash(product(1), 1);
        long saleEntry = entryFor("SALE", sale.get("orderNumber").asString()).get("id").asLong();
        assertThat(send("POST", "/accounting/journal/" + saleEntry + "/reverse", Map.of("reason", "No"), accountant).status()).isEqualTo(400);

        // The chart: new accounts; system accounts keep their type and stay on.
        Res cleaning = send("POST", "/accounting/accounts", Map.of("code", "6950", "name", "Cleaning", "type", "EXPENSE"), accountant);
        assertThat(cleaning.status()).as(String.valueOf(cleaning.body())).isEqualTo(201);
        assertThat(send("POST", "/accounting/accounts", Map.of("code", "6950", "name", "Again", "type", "EXPENSE"), accountant).status())
                .isEqualTo(409);
        long sales = account("4000");
        assertThat(send("PUT", "/accounting/accounts/" + sales, Map.of("code", "4000", "name", "Sales", "type", "OTHER_INCOME"), accountant)
                .status()).isEqualTo(400);
        assertThat(send("PUT", "/accounting/accounts/" + sales, Map.of("code", "4000", "name", "Sales", "type", "REVENUE", "active", false),
                accountant).status()).isEqualTo(400);
        assertThat(send("PUT", "/accounting/accounts/" + sales, Map.of("code", "4000", "name", "Shop sales", "type", "REVENUE"), accountant)
                .body().get("name").asString()).isEqualTo("Shop sales");
        send("PUT", "/accounting/accounts/" + sales, Map.of("code", "4000", "name", "Sales", "type", "REVENUE"), accountant);

        // Closing a period: nothing more can be entered on or before it; sales still post, on the next open day.
        assertThat(send("PUT", "/accounting/lock", Map.of("lockDate", TODAY.toString()), accountant).body().get("lockDate").asString())
                .isEqualTo(TODAY.toString());
        assertThat(send("POST", "/accounting/journal", entry, accountant).status()).isEqualTo(400);
        JsonNode late = sellCash(product(1), 1);
        assertThat(entryFor("SALE", late.get("orderNumber").asString(), TODAY.plusDays(1)).get("date").asString())
                .isEqualTo(TODAY.plusDays(1).toString());
        Map<String, Object> reopen = new HashMap<>();
        reopen.put("lockDate", null);
        assertThat(send("PUT", "/accounting/lock", reopen, accountant).body().get("lockDate").isNull()).isTrue();

        // In the audit log.
        JsonNode audit = get("/audit-logs?category=ACCOUNTING&size=50", admin).body();
        assertThat(audit.toString()).contains("Started the books", "Posted JE-", "Reversed JE-", "Closed the books", "Added account 6950");
    }

    @Test
    void normalAccountingLeavesOutJournalsAndTheBalanceSheet() {
        assertThat(get("/accounting/settings", accountant).body().get("mode").asString()).isEqualTo("FULL");
        assertThat(send("PUT", "/accounting/mode", Map.of("mode", "NORMAL"), manager).status()).isEqualTo(403);
        assertThat(send("PUT", "/accounting/mode", Map.of("mode", "NORMAL"), accountant).body().get("mode").asString()).isEqualTo("NORMAL");
        try {
            // Sales still post, and the journal and chart of accounts can still be read.
            JsonNode sale = sellCash(product(2), 1);
            long saleEntry = entryFor("SALE", sale.get("orderNumber").asString()).get("id").asLong();
            assertThat(get("/accounting/accounts", manager).status()).isEqualTo(200);
            // Manual entries, reversals, the balance sheet and trial balance are full accounting only.
            Map<String, Object> entry = Map.of("date", TODAY.toString(), "memo", "Loan", "lines", List.of(
                    Map.of("accountId", account("1110"), "debit", BigDecimal.TEN), Map.of("accountId", account("2500"), "credit", BigDecimal.TEN)));
            assertThat(send("POST", "/accounting/journal", entry, accountant).status()).isEqualTo(409);
            assertThat(send("POST", "/accounting/journal/" + saleEntry + "/reverse", Map.of("reason", "x"), accountant).status()).isEqualTo(409);
            assertThat(get("/reports/catalog", accountant).body().toString()).contains("profit-and-loss").doesNotContain("balance-sheet", "trial-balance");
            assertThat(get("/reports/run/balance-sheet", accountant).status()).isEqualTo(409);
            // Income and expenses work as always.
            assertThat(send("POST", "/accounting/other", Map.of("kind", "EXPENSE", "date", TODAY.toString(), "accountId", account("6300"),
                    "moneyAccountId", account("1001"), "amount", new BigDecimal("4.00")), manager).status()).isEqualTo(201);
        } finally {
            send("PUT", "/accounting/mode", Map.of("mode", "FULL"), accountant);
        }
        assertThat(get("/reports/catalog", accountant).body().toString()).contains("balance-sheet");
        assertThat(get("/audit-logs?category=ACCOUNTING&size=50", admin).body().toString()).contains("Switched to normal accounting");
    }

    // ---------------------------------------------------------------- helpers

    /** The Inventory account and the stock valuation agree. */
    private void assertInventoryFollowsStock() {
        JsonNode o = get("/accounting/overview", accountant).body();
        assertThat(dec(o, "inventoryBooks")).as("Inventory account vs stock valuation").isEqualByComparingTo(dec(o, "inventoryStock"));
    }

    private JsonNode sellCash(JsonNode p, int qty) {
        Res r = send("POST", "/orders", Map.of("items", List.of(Map.of("offeringId", p.get("id").asLong(), "quantity", qty)),
                "paymentMethod", "CASH"), cashier);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return r.body();
    }

    private JsonNode journal(String query) {
        return get("/accounting/journal?from=" + TODAY.minusYears(1) + "&to=" + TODAY.plusDays(2) + "&size=100&" + query, accountant).body();
    }

    private JsonNode entryFor(String source, String ref) {
        return entryFor(source, ref, null);
    }

    private JsonNode entryFor(String source, String ref, LocalDate date) {
        JsonNode items = journal("source=" + source + "&q=" + ref).get("items");
        assertThat(items).as(source + " entry for " + ref).hasSize(1);
        JsonNode e = items.get(0);
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        for (JsonNode l : e.get("lines")) {
            dr = dr.add(dec(l, "debit"));
            cr = cr.add(dec(l, "credit"));
        }
        assertThat(dr).as("balanced").isEqualByComparingTo(cr);
        if (date != null) {
            assertThat(e.get("date").asString()).isEqualTo(date.toString());
        }
        return e;
    }

    private static BigDecimal debit(JsonNode entry, String code) {
        return side(entry, code, "debit");
    }

    private static BigDecimal credit(JsonNode entry, String code) {
        return side(entry, code, "credit");
    }

    private static BigDecimal side(JsonNode entry, String code, String field) {
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode l : entry.get("lines")) {
            if (l.get("accountCode").asString().equals(code)) {
                sum = sum.add(dec(l, field));
            }
        }
        return sum;
    }

    private static BigDecimal amountOf(JsonNode report, String label) {
        for (JsonNode r : report.get("rows")) {
            if (label.equals(r.get("account").asString())) {
                return dec(r, "amount");
            }
        }
        throw new AssertionError(label);
    }

    private long account(String code) {
        for (JsonNode a : get("/accounting/accounts", accountant).body()) {
            if (a.get("code").asString().equals(code)) {
                return a.get("id").asLong();
            }
        }
        throw new AssertionError("account " + code);
    }

    private JsonNode product(int n) {
        return get("/offerings?type=PRODUCT&size=100", admin).body().get("items").get(n);
    }

    private long sellingStorage() {
        for (JsonNode b : get("/branches", admin).body()) {
            if (b.get("code").asString().equals("MAIN")) {
                for (JsonNode s : b.get("storages")) {
                    if (s.get("selling").asBoolean()) {
                        return s.get("id").asLong();
                    }
                }
            }
        }
        throw new AssertionError("storage");
    }

    private static BigDecimal dec(JsonNode n, String f) {
        return n.get(f).decimalValue();
    }

    private String login(String user, String password) {
        return send("POST", "/auth/login", Map.of("username", user, "password", password), null).body().get("accessToken").asString();
    }

    private Res get(String path, String token) {
        return send("GET", path, null, token);
    }

    private Res send(String method, String path, Object body, String token) {
        var spec = http.method(HttpMethod.valueOf(method)).uri(path);
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(body));
        }
        return spec.exchange((req, res) -> {
            byte[] bytes = res.getBody().readAllBytes();
            return new Res(res.getStatusCode().value(), bytes.length == 0 ? null : JSON.readTree(bytes));
        });
    }
}
