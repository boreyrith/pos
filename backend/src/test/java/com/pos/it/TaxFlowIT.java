package com.pos.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End to end through the HTTP API against a real MySQL (Flyway migrations, demo data): the three
 * tax modes, historical orders, validation, partial and full returns, packages, concurrent
 * checkouts and the tax report.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaxFlowIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    RestClient http;
    String admin;
    String manager;
    String cashier;

    record Res(int status, JsonNode body) {
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api").build();
        admin = login("admin", "admin1234");
        manager = login("manager", "manager1234");
        cashier = login("cashier", "cashier1234");
    }

    // --- modes -------------------------------------------------------------------------------

    @Test
    void eachModeChargesAsConfigured() {
        JsonNode product = stockedProduct(0);
        BigDecimal price = dec(product, "price");

        setTax("NONE", "0.10", true, true, true, true);
        JsonNode a = sell(List.of(line(product, 1)), null, null);
        assertThat(dec(a, "taxAmount")).isZero();
        assertThat(dec(a, "total")).isEqualByComparingTo(price);
        assertThat(a.get("taxMode").asString()).isEqualTo("NONE");
        // No tax report while the store charges no VAT.
        assertThat(get("/reports/tax", manager).status()).isEqualTo(409);

        setTax("INCLUSIVE", "0.10", true, true, true, true);
        JsonNode b = sell(List.of(line(product, 1)), null, null);
        BigDecimal included = price.multiply(new BigDecimal("0.10")).divide(new BigDecimal("1.10"), 2, RoundingMode.HALF_UP);
        assertThat(dec(b, "taxIncluded")).isEqualByComparingTo(included);
        assertThat(dec(b, "total")).isEqualByComparingTo(price);

        setTax("EXCLUSIVE", "0.10", true, true, true, true);
        JsonNode c = sell(List.of(line(product, 1)), null, null);
        BigDecimal added = price.multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP);
        assertThat(dec(c, "taxAmount")).isEqualByComparingTo(added);
        assertThat(dec(c, "taxIncluded")).isZero();
        assertThat(dec(c, "total")).isEqualByComparingTo(price.add(added));
        assertConsistent(a);
        assertConsistent(b);
        assertConsistent(c);
    }

    @Test
    void scopeAndExemptLinesAreNotTaxed() {
        JsonNode product = stockedProduct(0);
        JsonNode service = byType("SERVICE", false);
        setTax("EXCLUSIVE", "0.10", false, true, true, true);
        JsonNode o = sell(List.of(line(product, 2), line(service, 1)), "PERCENT", "10");
        assertConsistent(o);
        BigDecimal productNet = o.get("items").get(0).get("lineNet").decimalValue();
        assertThat(o.get("items").get(0).get("lineTax").decimalValue()).isZero();
        assertThat(o.get("items").get(0).get("taxName").asString()).isEqualTo("Not taxed");
        assertThat(dec(o, "nonTaxableAmount")).isEqualByComparingTo(productNet);
        assertThat(o.get("items").get(1).get("lineTax").decimalValue()).isPositive();
    }

    @Test
    void historicalOrdersKeepTheirTaxWhenSettingsChange() {
        setTax("EXCLUSIVE", "0.10", true, true, true, true);
        JsonNode before = sell(List.of(line(stockedProduct(1), 2)), "AMOUNT", "0.50");

        setTax("NONE", "0.07", true, true, true, true);
        setTax("INCLUSIVE", "0.07", true, true, true, true);
        JsonNode after = get("/orders/" + before.get("id").asLong(), manager).body();

        for (String f : List.of("subtotal", "discountAmount", "taxAmount", "taxIncluded", "taxableAmount", "total")) {
            assertThat(dec(after, f)).as(f).isEqualByComparingTo(dec(before, f));
        }
        assertThat(after.get("taxMode").asString()).isEqualTo("EXCLUSIVE");
        assertThat(after.get("items").get(0).get("taxRate").decimalValue()).isEqualByComparingTo("0.10");
        setTax("EXCLUSIVE", "0.10", true, true, true, true);
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThat(putTax(admin, "EXCLUSIVE", "0.35", "0.30").status()).isEqualTo(400);
        assertThat(putTax(admin, "EXCLUSIVE", "-0.01", "0.30").status()).isEqualTo(400);
        assertThat(putTax(admin, "EXCLUSIVE", "0.10", "1.00").status()).isEqualTo(400);
        assertThat(putTax(manager, "EXCLUSIVE", "0.10", "0.30").status()).isEqualTo(403);
        assertThat(get("/settings/tax", cashier).status()).isEqualTo(403);
        Res rate = send("POST", "/tax-rates", Map.of("name", "Too high", "rate", new BigDecimal("0.50"), "active", true), admin);
        assertThat(rate.status()).isEqualTo(400);
        // Nothing changed.
        assertThat(get("/settings/tax", manager).body().get("maxRate").decimalValue()).isEqualByComparingTo("0.30");
    }

    // --- returns -----------------------------------------------------------------------------

    @Test
    void partialThenFullRefundGivesBackExactlyWhatWasPaid() {
        setTax("EXCLUSIVE", "0.10", true, true, true, true);
        JsonNode p0 = stockedProduct(2);
        JsonNode p1 = stockedProduct(3);
        int stock0 = stock(p0);
        JsonNode order = sell(List.of(line(p0, 3), line(p1, 1)), "PERCENT", "15");
        assertThat(stock(p0)).isEqualTo(stock0 - 3);
        JsonNode item = order.get("items").get(0);
        BigDecimal paid = item.get("linePaid").decimalValue();
        BigDecimal tax = item.get("lineTax").decimalValue();

        JsonNode r1 = send("POST", "/orders/" + order.get("id").asLong() + "/returns",
                Map.of("lines", List.of(Map.of("orderItemId", item.get("id").asLong(), "quantity", 1)), "reason", "Damaged"), manager).body();
        assertThat(r1.get("status").asString()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(dec(r1, "refundedTotal")).isEqualByComparingTo(paid.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP));
        assertThat(dec(r1, "refundedTax")).isEqualByComparingTo(tax.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP));
        assertThat(stock(p0)).isEqualTo(stock0 - 2);

        Res tooMany = send("POST", "/orders/" + order.get("id").asLong() + "/returns",
                Map.of("lines", List.of(Map.of("orderItemId", item.get("id").asLong(), "quantity", 3))), manager);
        assertThat(tooMany.status()).isEqualTo(400);
        assertThat(send("POST", "/orders/" + order.get("id").asLong() + "/returns",
                Map.of("lines", List.of(Map.of("orderItemId", item.get("id").asLong(), "quantity", 1))), cashier).status()).isEqualTo(403);

        JsonNode done = send("POST", "/orders/" + order.get("id").asLong() + "/refund", null, manager).body();
        assertThat(done.get("status").asString()).isEqualTo("REFUNDED");
        assertThat(dec(done, "refundedTotal")).isEqualByComparingTo(dec(done, "total"));
        assertThat(dec(done, "refundedTax")).isEqualByComparingTo(dec(done, "taxAmount"));
        assertThat(done.get("returns")).hasSize(2);
        assertThat(stock(p0)).isEqualTo(stock0);
        assertThat(send("POST", "/orders/" + order.get("id").asLong() + "/refund", null, manager).status()).isEqualTo(409);

        // Both show in the list of returns, newest first; the second refunded the rest.
        JsonNode returns = get("/orders/returns?q=" + order.get("orderNumber").asString(), manager).body().get("items");
        assertThat(returns).hasSize(2);
        assertThat(returns.get(0).get("fullRefund").asBoolean()).isTrue();
        assertThat(returns.get(1).get("fullRefund").asBoolean()).isFalse();
        assertThat(returns.get(1).get("reason").asString()).isEqualTo("Damaged");
        assertThat(returns.get(1).get("items").asString()).contains("1 × ");
        assertThat(get("/orders/returns", cashier).status()).isEqualTo(200);
        assertThat(get("/orders/returns", login("stock", "stock12345")).status()).isEqualTo(403);
    }

    @Test
    void packageIsTaxedOnceAndReturnsItsContents() {
        setTax("INCLUSIVE", "0.10", true, true, true, true);
        JsonNode p0 = stockedProduct(4);
        JsonNode p1 = stockedProduct(5);
        JsonNode pkg = send("POST", "/offerings", Map.of(
                "type", "PACKAGE", "name", "IT bundle " + System.nanoTime(), "nameKm", "កញ្ចប់", "price", new BigDecimal("5.00"),
                "contents", List.of(
                        Map.of("componentId", p0.get("id").asLong(), "quantity", 2, "unitId", p0.get("unitId").asLong()),
                        Map.of("componentId", p1.get("id").asLong(), "quantity", 1, "unitId", p1.get("unitId").asLong()))), admin).body();
        assertThat(pkg.get("id")).as(pkg.toString()).isNotNull();
        int s0 = stock(p0);

        JsonNode o = sell(List.of(line(pkg, 2)), null, null);
        assertConsistent(o);
        assertThat(o.get("taxes")).hasSize(1);
        assertThat(dec(o, "total")).isEqualByComparingTo("10.00");
        assertThat(dec(o, "taxIncluded")).isEqualByComparingTo("0.91");
        assertThat(stock(p0)).isEqualTo(s0 - 4);

        JsonNode r = send("POST", "/orders/" + o.get("id").asLong() + "/returns",
                Map.of("lines", List.of(Map.of("orderItemId", o.get("items").get(0).get("id").asLong(), "quantity", 1))), manager).body();
        assertThat(dec(r, "refundedTotal")).isEqualByComparingTo("5.00");
        assertThat(dec(r, "refundedTax")).isEqualByComparingTo("0.46");
        assertThat(stock(p0)).isEqualTo(s0 - 2);
    }

    // --- concurrency -------------------------------------------------------------------------

    @Test
    void concurrentCheckoutsWhileSettingsChangeStayConsistent() throws Exception {
        setTax("EXCLUSIVE", "0.10", true, true, true, true);
        JsonNode product = stockedProduct(6);
        int before = stock(product);
        int sales = 12;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<JsonNode>> jobs = new ArrayList<>();
            for (int i = 0; i < sales; i++) {
                jobs.add(() -> sell(List.of(line(product, 1)), "PERCENT", "5"));
            }
            Future<?> flipper = pool.submit(() -> {
                for (int i = 0; i < 4; i++) {
                    setTax(i % 2 == 0 ? "INCLUSIVE" : "EXCLUSIVE", "0.10", true, true, true, true);
                }
            });
            List<JsonNode> orders = new ArrayList<>();
            for (Future<JsonNode> f : pool.invokeAll(jobs)) {
                orders.add(f.get());
            }
            flipper.get();
            for (JsonNode o : orders) {
                assertConsistent(o);
                // Every order is entirely one mode: never taxed twice or half-and-half.
                boolean inclusive = o.get("taxMode").asString().equals("INCLUSIVE");
                assertThat(dec(o, inclusive ? "taxAmount" : "taxIncluded")).isEqualByComparingTo(inclusive ? dec(o, "taxIncluded") : BigDecimal.ZERO);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(stock(product)).isEqualTo(before - sales);
        setTax("EXCLUSIVE", "0.10", true, true, true, true);
    }

    // --- reports -----------------------------------------------------------------------------

    @Test
    void taxReportMatchesOrdersAndReturns() {
        setTax("EXCLUSIVE", "0.10", true, true, true, true);
        String today = LocalDate.now(ZoneId.of("Asia/Phnom_Penh")).toString();
        String q = "/reports/tax?from=" + today + "&to=" + today;
        JsonNode before = get(q, manager).body().get("summary");

        JsonNode o = sell(List.of(line(stockedProduct(7), 2)), null, null);
        JsonNode r = send("POST", "/orders/" + o.get("id").asLong() + "/returns",
                Map.of("lines", List.of(Map.of("orderItemId", o.get("items").get(0).get("id").asLong(), "quantity", 1))), manager).body();
        JsonNode after = get(q, manager).body().get("summary");

        assertThat(dec(after, "taxCollected").subtract(dec(before, "taxCollected"))).isEqualByComparingTo(dec(o, "taxAmount"));
        assertThat(dec(after, "taxAdded").subtract(dec(before, "taxAdded"))).isEqualByComparingTo(dec(o, "taxAmount"));
        assertThat(dec(after, "grossSales").subtract(dec(before, "grossSales"))).isEqualByComparingTo(dec(o, "total"));
        assertThat(dec(after, "taxRefunded").subtract(dec(before, "taxRefunded"))).isEqualByComparingTo(dec(r, "refundedTax"));
        assertThat(dec(after, "refunds").subtract(dec(before, "refunds"))).isEqualByComparingTo(dec(r, "refundedTotal"));
        assertThat(dec(after, "netTax")).isEqualByComparingTo(dec(after, "taxCollected").subtract(dec(after, "taxRefunded")));

        // The whole report agrees with the database.
        BigDecimal dbTax = jdbc.sql("SELECT COALESCE(SUM(tax), 0) FROM order_taxes t JOIN orders o ON o.id = t.order_id "
                + "WHERE o.status IN ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')").query(BigDecimal.class).single();
        // Everything in this fresh database was sold within the last year.
        String yearAgo = LocalDate.parse(today).minusDays(365).toString();
        JsonNode all = get("/reports/tax?from=" + yearAgo + "&to=" + today, manager).body();
        assertThat(get("/reports/tax?from=2020-01-01&to=" + today, manager).status()).isEqualTo(400);
        assertThat(dec(all.get("summary"), "taxCollected")).isEqualByComparingTo(dbTax);
        BigDecimal byRate = BigDecimal.ZERO;
        for (JsonNode row : all.get("byRate")) {
            byRate = byRate.add(row.get("netTax").decimalValue());
        }
        assertThat(byRate).isEqualByComparingTo(dec(all.get("summary"), "netTax"));

        String csv = http.get().uri("/reports/tax.csv?from=" + today + "&to=" + today).header("Authorization", "Bearer " + manager)
                .retrieve().body(String.class);
        assertThat(csv).contains("Net tax due").contains(o.get("orderNumber").asString()).contains("RETURN");
    }

    // --- price overrides -----------------------------------------------------------------------

    @Test
    void priceOverridesNeedPermissionReasonAndCostRule() {
        setTax("EXCLUSIVE", "0.10", true, true, true, true);
        JsonNode product = null;
        for (JsonNode o : get("/offerings?type=PRODUCT&size=100", admin).body().get("items")) {
            if (o.get("stock").asInt() >= 30 && o.get("cost").decimalValue().signum() > 0 && o.get("taxRate").decimalValue().signum() > 0) {
                product = o;
                break;
            }
        }
        assertThat(product).as("a stocked product with a cost").isNotNull();
        BigDecimal cost = dec(product, "cost");
        BigDecimal listPrice = dec(product, "price");
        BigDecimal above = cost.add(new BigDecimal("0.01")).max(listPrice.subtract(new BigDecimal("0.10")));
        BigDecimal below = cost.subtract(new BigDecimal("0.01")).max(BigDecimal.ZERO);
        Map<String, Object> changed = Map.of("offeringId", product.get("id").asLong(), "quantity", 2, "unitPrice", above,
                "priceReason", "Damaged box");

        // The catalog price is the default for everyone; an unchanged "override" is fine without permission.
        assertThat(send("POST", "/orders", Map.of("items", List.of(Map.of("offeringId", product.get("id").asLong(), "quantity", 1,
                "unitPrice", listPrice)), "paymentMethod", "CASH"), cashier).status()).isEqualTo(201);
        // Cashiers can't change prices.
        assertThat(send("POST", "/orders", Map.of("items", List.of(changed), "paymentMethod", "CASH"), cashier).status()).isEqualTo(403);
        // Managers can, with a reason, but not below cost.
        assertThat(send("POST", "/orders", Map.of("items", List.of(Map.of("offeringId", product.get("id").asLong(), "quantity", 1,
                "unitPrice", above)), "paymentMethod", "CASH"), manager).status()).isEqualTo(400);
        assertThat(send("POST", "/orders", Map.of("items", List.of(Map.of("offeringId", product.get("id").asLong(), "quantity", 1,
                "unitPrice", below, "priceReason", "Clearance")), "paymentMethod", "CASH"), manager).status()).isEqualTo(403);

        Res ok = send("POST", "/orders", Map.of("items", List.of(changed), "paymentMethod", "CASH", "discountType", "PERCENT",
                "discountValue", new BigDecimal("10")), manager);
        assertThat(ok.status()).as(String.valueOf(ok.body())).isEqualTo(201);
        JsonNode item = ok.body().get("items").get(0);
        assertThat(item.get("priceOverridden").asBoolean()).isTrue();
        assertThat(item.get("listPrice").decimalValue()).isEqualByComparingTo(listPrice);
        assertThat(item.get("unitPrice").decimalValue()).isEqualByComparingTo(above);
        assertThat(item.get("priceOverrideReason").asString()).isEqualTo("Damaged box");
        assertThat(item.get("priceOverrideBy").asString()).isEqualTo("Sokha Manager");
        // Discount and VAT work on the new price.
        BigDecimal subtotal = above.multiply(BigDecimal.valueOf(2));
        assertThat(dec(ok.body(), "subtotal")).isEqualByComparingTo(subtotal);
        BigDecimal net = subtotal.subtract(subtotal.multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP));
        assertThat(dec(ok.body(), "taxAmount")).isEqualByComparingTo(net.multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP));
        assertConsistent(ok.body());
        // Admins may sell below cost.
        assertThat(send("POST", "/orders", Map.of("items", List.of(Map.of("offeringId", product.get("id").asLong(), "quantity", 1,
                "unitPrice", below, "priceReason", "Clearance")), "paymentMethod", "CASH"), admin).status()).isEqualTo(201);
        // Recorded in the audit log.
        String log = jdbc.sql("SELECT summary FROM audit_logs WHERE action = 'PRICE_OVERRIDDEN' ORDER BY id DESC LIMIT 1")
                .query(String.class).single();
        assertThat(log).contains("Clearance");
    }

    // --- item discounts ------------------------------------------------------------------------

    @Test
    void itemDiscountsNeedPermissionStayWithinTheRoleLimitAndComeBeforeTax() {
        setTax("EXCLUSIVE", "0.10", true, true, true, true);
        JsonNode p = stockedProduct(9);
        JsonNode q = stockedProduct(10);
        long pid = p.get("id").asLong();
        BigDecimal price = dec(p, "price");
        java.util.function.BiFunction<String, String, Map<String, Object>> discounted = (type, value) ->
                Map.of("offeringId", pid, "quantity", 4, "discountType", type, "discountValue", new BigDecimal(value));

        // Cashiers have no "Discount items"; managers up to 20%, as % or as an amount.
        assertThat(send("POST", "/orders", Map.of("items", List.of(discounted.apply("PERCENT", "10")), "paymentMethod", "CASH"), cashier).status())
                .isEqualTo(403);
        assertThat(send("POST", "/orders", Map.of("items", List.of(discounted.apply("PERCENT", "25")), "paymentMethod", "CASH"), manager).status())
                .isEqualTo(403);
        BigDecimal quarter = price.multiply(BigDecimal.valueOf(4)).multiply(new BigDecimal("0.25")).setScale(2, RoundingMode.HALF_UP);
        assertThat(send("POST", "/orders", Map.of("items", List.of(discounted.apply("AMOUNT", quarter.toPlainString())), "paymentMethod", "CASH"),
                manager).status()).isEqualTo(403);

        Res ok = send("POST", "/orders", Map.of("items", List.of(discounted.apply("PERCENT", "15"), line(q, 1)), "paymentMethod", "CASH",
                "discountType", "PERCENT", "discountValue", new BigDecimal("10")), manager);
        assertThat(ok.status()).as(String.valueOf(ok.body())).isEqualTo(201);
        JsonNode o = ok.body();
        BigDecimal gross1 = price.multiply(BigDecimal.valueOf(4));
        BigDecimal item = gross1.multiply(new BigDecimal("0.15")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal gross2 = dec(q, "price");
        assertThat(dec(o, "subtotal")).isEqualByComparingTo(gross1.add(gross2));
        assertThat(dec(o, "itemDiscount")).isEqualByComparingTo(item);
        BigDecimal after = gross1.subtract(item).add(gross2);
        BigDecimal orderDiscount = after.multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP);
        assertThat(dec(o, "discountAmount")).isEqualByComparingTo(orderDiscount);
        assertThat(dec(o, "taxAmount")).isEqualByComparingTo(after.subtract(orderDiscount).multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP));
        assertThat(dec(o, "total")).isEqualByComparingTo(after.subtract(orderDiscount).add(dec(o, "taxAmount")));
        JsonNode l1 = o.get("items").get(0);
        assertThat(l1.get("itemDiscount").decimalValue()).isEqualByComparingTo(item);
        assertThat(l1.get("itemDiscountType").asString()).isEqualTo("PERCENT");
        assertConsistentWithItems(o);

        // Exactly the limit is fine even when cent rounding makes the amount a hair more than 20%.
        JsonNode odd = null;
        for (JsonNode o2 : get("/offerings?type=PRODUCT&size=100", admin).body().get("items")) {
            long c = dec(o2, "price").movePointRight(2).longValueExact();
            if (o2.get("stock").asInt() >= 5 && (c * 20) % 100 >= 50) {
                odd = o2;
                break;
            }
        }
        if (odd != null) {
            assertThat(send("POST", "/orders", Map.of("items", List.of(Map.of("offeringId", odd.get("id").asLong(), "quantity", 1,
                    "discountType", "PERCENT", "discountValue", new BigDecimal("20"))), "paymentMethod", "CASH"), manager).status())
                    .isEqualTo(201);
        }

        // Admins have no limit; returns refund what the discounted line was paid.
        Res big = send("POST", "/orders", Map.of("items", List.of(discounted.apply("PERCENT", "60")), "paymentMethod", "CASH"), admin);
        assertThat(big.status()).isEqualTo(201);
        JsonNode line = big.body().get("items").get(0);
        Res ret = send("POST", "/orders/" + big.body().get("id").asLong() + "/returns",
                Map.of("lines", List.of(Map.of("orderItemId", line.get("id").asLong(), "quantity", 4))), admin);
        assertThat(dec(ret.body(), "refundedTotal")).isEqualByComparingTo(dec(big.body(), "total"));

        String log = jdbc.sql("SELECT summary FROM audit_logs WHERE action = 'ITEM_DISCOUNTED' ORDER BY id DESC LIMIT 1").query(String.class).single();
        assertThat(log).contains("60%");
    }

    /** Like assertConsistent, with item discounts counted. */
    private static void assertConsistentWithItems(JsonNode o) {
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        for (JsonNode i : o.get("items")) {
            paid = paid.add(i.get("linePaid").decimalValue());
            discount = discount.add(i.get("lineDiscount").decimalValue());
        }
        assertThat(paid).isEqualByComparingTo(dec(o, "total"));
        assertThat(discount).isEqualByComparingTo(dec(o, "discountAmount").add(dec(o, "itemDiscount")));
    }

    // --- held carts ----------------------------------------------------------------------------

    @Test
    void orderKeepsTheCartsCustomerAndNote() {
        setTax("EXCLUSIVE", "0.10", true, true, true, true);
        String customer = "Cust" + System.nanoTime();
        Res r = send("POST", "/orders", Map.of("items", List.of(line(stockedProduct(8), 1)), "paymentMethod", "CASH",
                "customerName", "  " + customer + " ", "note", "Table 4"), cashier);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        assertThat(r.body().get("customerName").asString()).isEqualTo(customer);
        assertThat(r.body().get("note").asString()).isEqualTo("Table 4");
        JsonNode found = get("/orders?q=" + customer, manager).body().get("items");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("customerName").asString()).isEqualTo(customer);
    }

    // --- helpers -----------------------------------------------------------------------------

    /** Lines add up to the order, and the tax split matches the order's tax. */
    private static void assertConsistent(JsonNode o) {
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        for (JsonNode i : o.get("items")) {
            paid = paid.add(i.get("linePaid").decimalValue());
            tax = tax.add(i.get("lineTax").decimalValue());
            discount = discount.add(i.get("lineDiscount").decimalValue());
        }
        assertThat(paid).as("lines paid = total").isEqualByComparingTo(dec(o, "total"));
        assertThat(tax).as("line tax = order tax").isEqualByComparingTo(dec(o, "taxAmount"));
        assertThat(discount).as("line discounts = discount").isEqualByComparingTo(dec(o, "discountAmount"));
        BigDecimal groups = BigDecimal.ZERO;
        for (JsonNode t : o.get("taxes")) {
            groups = groups.add(t.get("tax").decimalValue());
        }
        assertThat(groups).isEqualByComparingTo(dec(o, "taxAmount"));
        assertThat(dec(o, "total")).isEqualByComparingTo(dec(o, "subtotal").subtract(dec(o, "itemDiscount"))
                .subtract(dec(o, "discountAmount")).add(dec(o, "taxAmount")).subtract(dec(o, "taxIncluded")));
    }

    private void setTax(String mode, String rate, boolean products, boolean services, boolean items, boolean packages) {
        Res r = send("PUT", "/settings/tax", Map.of("mode", mode, "label", "VAT", "vatRate", new BigDecimal(rate),
                "maxRate", new BigDecimal("0.30"), "taxProducts", products, "taxServices", services, "taxItems", items,
                "taxPackages", packages), admin);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
    }

    private Res putTax(String token, String mode, String rate, String max) {
        return send("PUT", "/settings/tax", Map.of("mode", mode, "label", "VAT", "vatRate", new BigDecimal(rate),
                "maxRate", new BigDecimal(max), "taxProducts", true, "taxServices", true, "taxItems", true, "taxPackages", true), token);
    }

    private JsonNode sell(List<Map<String, Object>> lines, String discountType, String discountValue) {
        var body = new java.util.HashMap<String, Object>(Map.of("items", lines, "paymentMethod", "CASH"));
        if (discountType != null) {
            body.put("discountType", discountType);
            body.put("discountValue", new BigDecimal(discountValue));
        }
        Res r = send("POST", "/orders", body, cashier);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return r.body();
    }

    private static Map<String, Object> line(JsonNode offering, int qty) {
        return Map.of("offeringId", offering.get("id").asLong(), "quantity", qty);
    }

    /** The n-th product with plenty of stock and a positive tax rate; different tests use different ones. */
    private JsonNode stockedProduct(int n) {
        List<JsonNode> list = new ArrayList<>();
        for (JsonNode o : get("/offerings?type=PRODUCT&size=100", admin).body().get("items")) {
            if (o.get("stock").asInt() >= 30 && o.get("taxRate").decimalValue().signum() > 0) {
                list.add(o);
            }
        }
        list.sort((a, b) -> Long.compare(a.get("id").asLong(), b.get("id").asLong()));
        return list.get(n % list.size());
    }

    private JsonNode byType(String type, boolean hourly) {
        for (JsonNode o : get("/offerings?type=" + type + "&size=100", admin).body().get("items")) {
            if (("HOURLY".equals(o.get("pricing").asString())) == hourly && o.get("taxRate").decimalValue().signum() > 0) {
                return o;
            }
        }
        throw new AssertionError("No " + type);
    }

    private int stock(JsonNode offering) {
        return get("/offerings/" + offering.get("id").asLong(), manager).body().get("stock").asInt();
    }

    private static BigDecimal dec(JsonNode n, String field) {
        return n.get(field).decimalValue();
    }

    private String login(String user, String password) {
        return send("POST", "/auth/login", Map.of("username", user, "password", password), null).body().get("accessToken").asString();
    }

    private Res get(String path, String token) {
        return send("GET", path, null, token);
    }

    private Res send(String method, String path, Object body, String token) {
        var spec = http.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path);
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(body));
        }
        return spec.exchange((req, res) -> {
            HttpStatusCode status = res.getStatusCode();
            byte[] bytes = res.getBody().readAllBytes();
            return new Res(status.value(), bytes.length == 0 ? null : JSON.readTree(bytes));
        });
    }
}
