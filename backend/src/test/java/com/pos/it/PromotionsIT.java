package com.pos.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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

/** Promotions and coupons through checkout: amounts, coupons, limits, cancellations, refunds and permissions. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PromotionsIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

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

    @Test
    void aCategoryPromotionComesOffBeforeTheCashiersDiscounts() {
        JsonNode p = stocked(0);
        long promo = create(promotion("10% off " + p.get("categoryName").asString(), "ITEM_DISCOUNT", "PERCENT", "10",
                Map.of("scope", "CATEGORIES", "categoryIds", List.of(p.get("categoryId").asLong()))), manager);
        try {
            BigDecimal price = dec(p.get("price"));
            BigDecimal expectedPromo = price.multiply(BigDecimal.valueOf(2)).multiply(new BigDecimal("0.10")).setScale(2, java.math.RoundingMode.HALF_UP);

            // The sell screen's preview and the sale agree.
            JsonNode preview = send("POST", "/promotions/evaluate", Map.of("items", List.of(line(p, 2))), cashier).body();
            assertThat(dec(preview.get("lines").get(0).get("promoDiscount"))).isEqualByComparingTo(expectedPromo);

            // An order discount of 5% on top, worked out after the promotion.
            Map<String, Object> sale = sale(List.of(line(p, 2)), null);
            sale.put("discountType", "PERCENT");
            sale.put("discountValue", 5);
            JsonNode o = sold(sale);
            assertThat(dec(o.get("promoDiscount"))).isEqualByComparingTo(expectedPromo);
            assertThat(o.get("items").get(0).get("promotionName").asString()).startsWith("10% off");
            BigDecimal afterPromo = dec(o.get("subtotal")).subtract(expectedPromo);
            assertThat(dec(o.get("discountAmount"))).isEqualByComparingTo(afterPromo.multiply(new BigDecimal("0.05"))
                    .setScale(2, java.math.RoundingMode.HALF_UP));
            assertAddsUp(o);
            assertThat(o.get("promotions")).hasSize(1);

            JsonNode listed = find(get("/promotions", manager).body(), promo);
            assertThat(listed.get("usedCount").asInt()).isEqualTo(1);
            assertThat(dec(listed.get("discountGiven"))).isEqualByComparingTo(expectedPromo);

            // A refund gives back what was paid, promotion and all: never more.
            JsonNode refunded = send("POST", "/orders/" + o.get("id").asLong() + "/refund", null, admin).body();
            assertThat(dec(refunded.get("refundedTotal"))).isEqualByComparingTo(dec(o.get("total")));
        } finally {
            pause(promo);
        }
    }

    @Test
    void couponsAreCheckedAndSingleUseCodesWorkOnce() {
        JsonNode p = stocked(1);
        Map<String, Object> body = promotion("$2 off the order", "ORDER_DISCOUNT", "AMOUNT", "2.00", Map.of("requiresCode", true));
        long promo = create(body, manager);
        // A typed shared code, and a batch of single-use ones.
        assertThat(send("POST", "/promotions/" + promo + "/codes", Map.of("code", "save2-" + System.nanoTime() % 100000), manager).status())
                .isEqualTo(201);
        JsonNode batch = send("POST", "/promotions/" + promo + "/codes/generate", Map.of("count", 3, "prefix", "FLYER"), manager).body();
        assertThat(batch).hasSize(3);
        String code = batch.get(0).get("code").asString();
        assertThat(code).startsWith("FLYER-").hasSize(14);

        // Without the code: nothing. With it (any case): 2.00 off.
        assertThat(dec(sold(sale(List.of(line(p, 1)), null)).get("orderPromoDiscount"))).isEqualByComparingTo("0");
        JsonNode o = sold(sale(List.of(line(p, 1)), code.toLowerCase()));
        assertThat(dec(o.get("orderPromoDiscount"))).isEqualByComparingTo("2.00");
        assertThat(o.get("couponCode").asString()).isEqualTo(code);
        assertAddsUp(o);

        // Used once: refused now, with the reason.
        Res again = send("POST", "/orders", sale(List.of(line(p, 1)), code), cashier);
        assertThat(again.status()).isEqualTo(400);
        assertThat(again.body().get("detail").asString()).contains("already been used");
        JsonNode preview = send("POST", "/promotions/evaluate", Map.of("items", List.of(line(p, 1)), "couponCode", code), cashier).body();
        assertThat(preview.get("coupon").get("applied").asBoolean()).isFalse();
        assertThat(send("POST", "/promotions/evaluate", Map.of("items", List.of(line(p, 1)), "couponCode", "NOPE-123"), cashier)
                .body().get("coupon").get("message").asString()).isEqualTo("This code doesn't exist");
        pause(promo);
    }

    @Test
    void aCancelledKhqrSaleGivesItsCouponBack() {
        JsonNode p = stocked(2);
        long promo = create(promotion("Half off, once", "ORDER_DISCOUNT", "PERCENT", "50",
                Map.of("requiresCode", true, "usageLimit", 1)), manager);
        String code = send("POST", "/promotions/" + promo + "/codes/generate", Map.of("count", 1), manager).body().get(0).get("code").asString();

        Map<String, Object> khqr = sale(List.of(line(p, 1)), code);
        khqr.put("paymentMethod", "KHQR");
        JsonNode pending = sold(khqr);
        assertThat(pending.get("status").asString()).isEqualTo("PENDING_PAYMENT");
        // Held for the waiting sale: the limit of one is reached.
        assertThat(send("POST", "/orders", sale(List.of(line(p, 1)), code), cashier).status()).isEqualTo(400);

        assertThat(send("POST", "/orders/" + pending.get("id").asLong() + "/cancel", null, admin).status()).isEqualTo(200);
        assertThat(find(get("/promotions", manager).body(), promo).get("usedCount").asInt()).isZero();
        assertThat(dec(sold(sale(List.of(line(p, 1)), code)).get("orderPromoDiscount"))).isPositive();
        assertThat(find(get("/promotions", manager).body(), promo).get("status").asString()).isEqualTo("USED_UP");
    }

    @Test
    void buyTwoGetOneFreeGivesTheCheapestUnit() {
        JsonNode p = stocked(3);
        long promo = create(promotion("Buy 2 get 1 free", "BUY_X_GET_Y", "PERCENT", "100",
                Map.of("buyQty", 2, "getQty", 1, "scope", "OFFERINGS", "offeringIds", List.of(p.get("id").asLong()))), manager);
        try {
            JsonNode o = sold(sale(List.of(line(p, 3)), null));
            assertThat(dec(o.get("promoDiscount"))).isEqualByComparingTo(dec(p.get("price")));
            assertAddsUp(o);
        } finally {
            pause(promo);
        }
    }

    @Test
    void permissionsAndValidation() {
        Map<String, Object> body = promotion("Nope", "ITEM_DISCOUNT", "PERCENT", "10", Map.of());
        assertThat(send("POST", "/promotions", body, cashier).status()).isEqualTo(403);
        assertThat(get("/promotions", cashier).status()).isEqualTo(403);
        // The accountant sees, but can't change.
        String accountant = login("accountant", "accountant1234");
        assertThat(get("/promotions", accountant).status()).isEqualTo(200);
        assertThat(send("POST", "/promotions", body, accountant).status()).isEqualTo(403);

        assertThat(send("POST", "/promotions", promotion("Too much", "ITEM_DISCOUNT", "PERCENT", "150", Map.of()), admin).status())
                .isEqualTo(400);
        assertThat(send("POST", "/promotions", promotion("No targets", "ITEM_DISCOUNT", "PERCENT", "10",
                Map.of("scope", "CATEGORIES")), admin).status()).isEqualTo(400);
        // Codes only go on promotions that need one.
        long open = create(promotion("Open deal", "ITEM_DISCOUNT", "PERCENT", "1", Map.of("active", false)), admin);
        assertThat(send("POST", "/promotions/" + open + "/codes", Map.of("code", "OPEN1"), admin).status()).isEqualTo(400);
        // Unused: can be deleted.
        assertThat(send("DELETE", "/promotions/" + open, null, admin).status()).isEqualTo(204);
    }

    // ---------------------------------------------------------------- helpers

    /** The n-th product that has stock to sell, cheapest-first so line totals stay small. */
    private JsonNode stocked(int n) {
        List<JsonNode> withStock = new ArrayList<>();
        get("/offerings?type=PRODUCT&forSale=true&size=200", admin).body().get("items").forEach(o -> {
            if (o.get("stock").asInt() >= 10 && dec(o.get("price")).signum() > 0) {
                withStock.add(o);
            }
        });
        return withStock.get(n);
    }

    private static Map<String, Object> promotion(String name, String kind, String type, String value, Map<String, Object> extra) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("kind", kind);
        m.put("discountType", type);
        m.put("discountValue", new BigDecimal(value));
        m.put("scope", "ALL");
        m.put("priority", 0);
        m.put("active", true);
        m.putAll(extra);
        return m;
    }

    private long create(Map<String, Object> body, String token) {
        Res r = send("POST", "/promotions", body, token);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    /** Pauses a promotion so it doesn't touch the other tests' sales. */
    private void pause(long id) {
        JsonNode p = get("/promotions/" + id, admin).body();
        Map<String, Object> body = JSON.convertValue(p, Map.class);
        body.put("active", false);
        assertThat(send("PUT", "/promotions/" + id, body, admin).status()).isEqualTo(200);
    }

    private static Map<String, Object> line(JsonNode p, int qty) {
        return Map.of("offeringId", p.get("id").asLong(), "quantity", qty);
    }

    private static Map<String, Object> sale(List<Map<String, Object>> lines, String coupon) {
        Map<String, Object> m = new HashMap<>();
        m.put("items", lines);
        m.put("paymentMethod", "CASH");
        if (coupon != null) {
            m.put("couponCode", coupon);
        }
        return m;
    }

    private JsonNode sold(Map<String, Object> sale) {
        Res r = send("POST", "/orders", sale, cashier);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return r.body();
    }

    /** Every layer adds up to the total, and the lines add up to the order. */
    private static void assertAddsUp(JsonNode o) {
        BigDecimal expected = dec(o.get("subtotal")).subtract(dec(o.get("promoDiscount"))).subtract(dec(o.get("itemDiscount")))
                .subtract(dec(o.get("orderPromoDiscount"))).subtract(dec(o.get("discountAmount")))
                .add(dec(o.get("taxAmount"))).subtract(dec(o.get("taxIncluded")));
        assertThat(dec(o.get("total"))).isEqualByComparingTo(expected);
        BigDecimal lines = BigDecimal.ZERO;
        for (JsonNode i : o.get("items")) {
            lines = lines.add(dec(i.get("linePaid")));
        }
        assertThat(lines).isEqualByComparingTo(dec(o.get("total")));
    }

    private static JsonNode find(JsonNode list, long id) {
        for (JsonNode p : list) {
            if (p.get("id").asLong() == id) {
                return p;
            }
        }
        throw new AssertionError("No promotion " + id);
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
