package com.pos.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

/** Loyalty points through sales: earning, redeeming, cancellations, refunds, adjustments and permissions. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoyaltyIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    RestClient http;
    String admin;
    String cashier;

    record Res(int status, JsonNode body) {
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api").build();
        admin = login("admin", "admin1234");
        cashier = login("cashier", "cashier1234");
        // 1 point per 1.00 paid, a point is worth 0.05, use at least 20, up to half a sale.
        Res r = send("PUT", "/loyalty/rules", Map.of("enabled", true, "pointsPerUnit", 1, "pointValue", new BigDecimal("0.05"),
                "minRedeem", 20, "maxRedeemPct", 50), admin);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
    }

    @Test
    void customersEarnOnWhatTheyPayAndWalkInsDont() {
        long customer = newCustomer();
        JsonNode p = stocked(0);
        JsonNode o = sold(sale(customer, List.of(line(p, 2)), null));
        int expected = dec(o.get("total")).setScale(0, RoundingMode.DOWN).intValue();
        assertThat(o.get("pointsEarned").asInt()).isEqualTo(expected);
        assertThat(balance(customer)).isEqualTo(expected);

        JsonNode walkIn = sold(sale(null, List.of(line(p, 1)), null));
        assertThat(walkIn.get("pointsEarned").asInt()).isZero();
        assertThat(walkIn.get("customerPoints").isNull()).isTrue();

        JsonNode history = get("/loyalty/customers/" + customer + "/history", admin).body();
        assertThat(history.get(0).get("type").asString()).isEqualTo("EARN");
        assertThat(history.get(0).get("balanceAfter").asInt()).isEqualTo(expected);
    }

    @Test
    void redeemingFollowsTheRules() {
        long customer = newCustomer();
        adjust(customer, 100, "Opening balance from the old system");
        JsonNode p = stocked(1);
        BigDecimal subtotal = dec(p.get("price")).multiply(BigDecimal.valueOf(4));

        // Below the minimum, more than they have, more than half the sale: refused with the reason.
        assertThat(send("POST", "/orders", sale(customer, List.of(line(p, 4)), 10), cashier).body().get("detail").asString())
                .contains("at least 20");
        assertThat(send("POST", "/orders", sale(customer, List.of(line(p, 4)), 500), cashier).body().get("detail").asString())
                .contains("has 100 points");
        int most = subtotal.multiply(new BigDecimal("0.5")).divide(new BigDecimal("0.05"), 0, RoundingMode.DOWN).intValue();
        if (most < 100) {
            assertThat(send("POST", "/orders", sale(customer, List.of(line(p, 4)), most + 1), cashier).status()).isEqualTo(400);
        }
        // The walk-in has no points to use.
        assertThat(send("POST", "/orders", sale(null, List.of(line(p, 4)), 20), cashier).status()).isEqualTo(400);

        int use = Math.min(40, most);
        JsonNode o = sold(sale(customer, List.of(line(p, 4)), use));
        assertThat(o.get("pointsRedeemed").asInt()).isEqualTo(use);
        assertThat(dec(o.get("pointsDiscount"))).isEqualByComparingTo(new BigDecimal("0.05").multiply(BigDecimal.valueOf(use)));
        // Points come off before tax, and everything still adds up.
        BigDecimal expectedTotal = dec(o.get("subtotal")).subtract(dec(o.get("pointsDiscount")))
                .add(dec(o.get("taxAmount"))).subtract(dec(o.get("taxIncluded")));
        assertThat(dec(o.get("total"))).isEqualByComparingTo(expectedTotal);
        int earned = o.get("pointsEarned").asInt();
        assertThat(balance(customer)).isEqualTo(100 - use + earned);
    }

    @Test
    void aCancelledKhqrSaleGivesItsPointsBack() {
        long customer = newCustomer();
        adjust(customer, 60, "Welcome gift");
        Map<String, Object> khqr = sale(customer, List.of(line(stocked(2), 3)), 20);
        khqr.put("paymentMethod", "KHQR");
        JsonNode pending = sold(khqr);
        assertThat(balance(customer)).isEqualTo(40);
        // Nothing is earned until it is paid.
        assertThat(pending.get("pointsEarned").asInt()).isZero();
        assertThat(send("POST", "/orders/" + pending.get("id").asLong() + "/cancel", null, admin).status()).isEqualTo(200);
        assertThat(balance(customer)).isEqualTo(60);
    }

    @Test
    void refundsTakeBackEarnedPointsAndGiveBackUsedOnes() {
        long customer = newCustomer();
        adjust(customer, 200, "Top up for the test");
        JsonNode p = stocked(3);
        JsonNode o = sold(sale(customer, List.of(line(p, 4)), 20));
        int earned = o.get("pointsEarned").asInt();
        int before = balance(customer);

        // One unit back of four: about a quarter of each, rounded down.
        long lineId = o.get("items").get(0).get("id").asLong();
        JsonNode partial = send("POST", "/orders/" + o.get("id").asLong() + "/returns",
                Map.of("lines", List.of(Map.of("orderItemId", lineId, "quantity", 1))), admin).body();
        JsonNode ret = partial.get("returns").get(0);
        assertThat(ret.get("pointsReversed").asInt()).isLessThanOrEqualTo(earned);
        assertThat(ret.get("pointsRestored").asInt()).isEqualTo(5);
        int afterPartial = balance(customer);
        assertThat(afterPartial).isEqualTo(before - ret.get("pointsReversed").asInt() + 5);

        // The rest: everything settled exactly, nothing left over or taken twice.
        JsonNode full = send("POST", "/orders/" + o.get("id").asLong() + "/refund", null, admin).body();
        int reversed = 0;
        int restored = 0;
        for (JsonNode r : full.get("returns")) {
            reversed += r.get("pointsReversed").asInt();
            restored += r.get("pointsRestored").asInt();
        }
        assertThat(reversed).isEqualTo(earned);
        assertThat(restored).isEqualTo(20);
        assertThat(balance(customer)).isEqualTo(before - earned + 20);
    }

    @Test
    void tiersFollowSpendingAndEarnFaster() {
        JsonNode p = stocked(4);
        BigDecimal price = dec(p.get("price"));
        // Silver at 2 units' worth, Gold at 5, Platinum far away; Silver earns double.
        List<Map<String, Object>> tiers = List.of(
                Map.of("code", "BRONZE", "minSpend", 0, "pointsMultiplier", 1),
                Map.of("code", "SILVER", "minSpend", price.multiply(BigDecimal.valueOf(2)), "pointsMultiplier", 2),
                Map.of("code", "GOLD", "minSpend", price.multiply(BigDecimal.valueOf(5)), "pointsMultiplier", 3),
                Map.of("code", "PLATINUM", "minSpend", price.multiply(BigDecimal.valueOf(1000)), "pointsMultiplier", 4));
        Map<String, Object> rules = new HashMap<>(Map.of("enabled", true, "pointsPerUnit", 1, "pointValue", new BigDecimal("0.05"),
                "minRedeem", 20, "maxRedeemPct", 50, "tierMonths", 12, "tiers", tiers));
        assertThat(send("PUT", "/loyalty/rules", rules, admin).status()).isEqualTo(200);
        try {
            long customer = newCustomer();
            assertThat(tier(customer)).isEqualTo("BRONZE");

            JsonNode first = sold(sale(customer, List.of(line(p, 1)), null));
            assertThat(tier(customer)).isEqualTo("BRONZE");
            // This sale earns at Bronze, then takes them to Silver.
            JsonNode second = sold(sale(customer, List.of(line(p, 2)), null));
            assertThat(second.get("pointsEarned").asInt()).isEqualTo(dec(second.get("total")).setScale(0, RoundingMode.DOWN).intValue());
            assertThat(second.get("customerTier").asString()).isEqualTo("SILVER");
            // Now they earn double.
            JsonNode third = sold(sale(customer, List.of(line(p, 1)), null));
            assertThat(third.get("pointsEarned").asInt())
                    .isEqualTo(dec(third.get("total")).multiply(BigDecimal.valueOf(2)).setScale(0, RoundingMode.DOWN).intValue());

            // A Gold-and-above promotion isn't theirs yet; a Silver-and-above segment includes them.
            Map<String, Object> promo = new HashMap<>(Map.of("name", "Gold treat", "kind", "ORDER_DISCOUNT", "discountType", "AMOUNT",
                    "discountValue", 1, "requiresCode", true, "minTier", "GOLD"));
            long promotion = send("POST", "/promotions", promo, admin).body().get("id").asLong();
            String code = "GOLD" + Math.floorMod(System.nanoTime(), 1_000_000);
            assertThat(send("POST", "/promotions/" + promotion + "/codes", Map.of("code", code), admin).status()).isEqualTo(201);
            JsonNode preview = send("POST", "/promotions/evaluate", Map.of("items", List.of(line(p, 1)), "customerId", customer,
                    "couponCode", code), cashier).body();
            assertThat(preview.get("coupon").get("message").asString()).isEqualTo("This promotion is for Gold customers and above");
            JsonNode segment = send("POST", "/segments/preview", Map.of("minTier", "SILVER"), admin).body();
            assertThat(segment.get("count").asLong()).isPositive();

            // Refunds count against spending: back below Silver means back to Bronze.
            assertThat(send("POST", "/orders/" + third.get("id").asLong() + "/refund", null, admin).status()).isEqualTo(200);
            assertThat(send("POST", "/orders/" + second.get("id").asLong() + "/refund", null, admin).status()).isEqualTo(200);
            assertThat(tier(customer)).isEqualTo("BRONZE");
            assertThat(first.get("customerTier").asString()).isEqualTo("BRONZE");
        } finally {
            // Back to the usual tiers so the other tests earn at Bronze.
            rules.put("tiers", List.of(Map.of("code", "BRONZE", "minSpend", 0, "pointsMultiplier", 1),
                    Map.of("code", "SILVER", "minSpend", 300, "pointsMultiplier", 1.25), Map.of("code", "GOLD", "minSpend", 1000, "pointsMultiplier", 1.5),
                    Map.of("code", "PLATINUM", "minSpend", 3000, "pointsMultiplier", 2)));
            send("PUT", "/loyalty/rules", rules, admin);
        }
    }

    @Test
    void tierRulesAreChecked() {
        Map<String, Object> rules = new HashMap<>(Map.of("enabled", true, "pointsPerUnit", 1, "pointValue", 1, "minRedeem", 1,
                "maxRedeemPct", 10, "tiers", List.of(Map.of("code", "BRONZE", "minSpend", 0, "pointsMultiplier", 1),
                        Map.of("code", "SILVER", "minSpend", 500, "pointsMultiplier", 1), Map.of("code", "GOLD", "minSpend", 400, "pointsMultiplier", 1),
                        Map.of("code", "PLATINUM", "minSpend", 900, "pointsMultiplier", 1))));
        assertThat(send("PUT", "/loyalty/rules", rules, admin).body().get("detail").asString())
                .isEqualTo("Each tier needs a higher spend than the one below it");
    }

    private String tier(long customer) {
        return get("/customers/" + customer, admin).body().get("customer").get("tier").asString();
    }

    @Test
    void permissions() {
        long customer = newCustomer();
        // Cashiers read the rules and a balance to sell, but can't change them.
        assertThat(get("/loyalty/rules", cashier).status()).isEqualTo(200);
        assertThat(get("/loyalty/customers/" + customer, cashier).status()).isEqualTo(200);
        assertThat(send("PUT", "/loyalty/rules", Map.of("enabled", false, "pointsPerUnit", 1, "pointValue", 1, "minRedeem", 1,
                "maxRedeemPct", 10), cashier).status()).isEqualTo(403);
        assertThat(send("POST", "/loyalty/customers/" + customer + "/adjust", Map.of("points", 5, "note", "nope"), cashier).status())
                .isEqualTo(403);
        assertThat(get("/loyalty/overview", cashier).status()).isEqualTo(403);
        // Managers manage loyalty.
        assertThat(send("POST", "/loyalty/customers/" + customer + "/adjust", Map.of("points", 5, "note", "Birthday"),
                login("manager", "manager1234")).status()).isEqualTo(200);
        assertThat(get("/loyalty/overview", admin).body().get("members").asLong()).isPositive();
    }

    // ---------------------------------------------------------------- helpers

    private long newCustomer() {
        String phone = "09" + (10_000_000 + Math.floorMod(System.nanoTime(), 89_999_999));
        Res r = send("POST", "/customers", Map.of("name", "Loyal " + phone, "phone", phone), admin);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    private void adjust(long customer, int points, String note) {
        assertThat(send("POST", "/loyalty/customers/" + customer + "/adjust", Map.of("points", points, "note", note), admin).status())
                .isEqualTo(200);
    }

    private int balance(long customer) {
        return get("/loyalty/customers/" + customer, cashier).body().get("points").asInt();
    }

    private JsonNode stocked(int n) {
        List<JsonNode> withStock = new ArrayList<>();
        get("/offerings?type=PRODUCT&forSale=true&size=200", admin).body().get("items").forEach(o -> {
            if (o.get("stock").asInt() >= 10 && dec(o.get("price")).compareTo(BigDecimal.TEN) >= 0) {
                withStock.add(o);
            }
        });
        return withStock.get(n);
    }

    private static Map<String, Object> line(JsonNode p, int qty) {
        return Map.of("offeringId", p.get("id").asLong(), "quantity", qty);
    }

    private static Map<String, Object> sale(Long customer, List<Map<String, Object>> lines, Integer points) {
        Map<String, Object> m = new HashMap<>();
        m.put("items", lines);
        m.put("paymentMethod", "CASH");
        if (customer != null) {
            m.put("customerId", customer);
        }
        if (points != null) {
            m.put("redeemPoints", points);
        }
        return m;
    }

    private JsonNode sold(Map<String, Object> sale) {
        Res r = send("POST", "/orders", sale, cashier);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return r.body();
    }

    private static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
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
