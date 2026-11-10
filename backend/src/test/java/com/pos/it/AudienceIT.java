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

/** Segments and campaigns: who is in a segment, personal codes, export, results and permissions. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AudienceIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    RestClient http;
    String admin;
    String cashier;

    record Res(int status, JsonNode body, String text) {
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api").build();
        admin = login("admin", "admin1234");
        cashier = login("cashier", "cashier1234");
    }

    @Test
    void segmentsCampaignsCodesAndResults() {
        JsonNode p = stocked(0);
        BigDecimal price = dec(p.get("price"));
        // A big spender, someone who bought once, and someone who never bought but left an email.
        long big = customer("Big Spender", "big@example.com");
        long once = customer("Once Buyer", null);
        long never = customer("Never Bought", "never@example.com");
        sold(sale(big, p, 6, null));
        sold(sale(big, p, 4, null));
        sold(sale(once, p, 1, null));

        BigDecimal threshold = price.multiply(BigDecimal.valueOf(5));
        Map<String, Object> bigRules = Map.of("minSpent", threshold, "boughtWithinDays", 30);
        JsonNode preview = send("POST", "/segments/preview", bigRules, admin).body();
        assertThat(preview.get("count").asLong()).isEqualTo(1);
        assertThat(preview.get("sample").get(0).get("id").asLong()).isEqualTo(big);
        assertThat(preview.get("sample").get(0).get("orders").asLong()).isEqualTo(2);
        // Email on file, never bought (no spend, no orders).
        JsonNode reachable = send("POST", "/segments/preview", Map.of("hasEmail", true, "maxOrders", 0), admin).body();
        assertThat(ids(reachable.get("sample"))).contains(never).doesNotContain(big, once);

        long segment = send("POST", "/segments", Map.of("name", "Big spenders", "rules", bigRules), admin).body().get("id").asLong();
        assertThat(find(get("/segments", admin).body(), segment).get("members").asLong()).isEqualTo(1);

        // A coupon promotion; the campaign gives each member a code of their own.
        Map<String, Object> promo = new HashMap<>(Map.of("name", "Thank-you 15%", "kind", "ORDER_DISCOUNT", "discountType", "PERCENT",
                "discountValue", 15, "requiresCode", true));
        long promotion = send("POST", "/promotions", promo, admin).body().get("id").asLong();

        Map<String, Object> campaign = new HashMap<>(Map.of("name", "Thank you", "segmentId", segment,
                "message", "Hi {name}, thank you! Use {code} for 15% off at {store}.", "promotionId", promotion, "codePrefix", "THX"));
        // {code} needs a promotion, and a promotion needs {code} in the message.
        Map<String, Object> noCode = new HashMap<>(campaign);
        noCode.remove("promotionId");
        assertThat(send("POST", "/campaigns", noCode, admin).status()).isEqualTo(400);

        long id = send("POST", "/campaigns", campaign, admin).body().get("id").asLong();
        JsonNode prepared = send("POST", "/campaigns/" + id + "/prepare", null, admin).body();
        assertThat(prepared.get("status").asString()).isEqualTo("PREPARED");
        assertThat(prepared.get("recipients").asInt()).isEqualTo(1);
        // Done once: it can't be prepared or changed again.
        assertThat(send("POST", "/campaigns/" + id + "/prepare", null, admin).status()).isEqualTo(409);

        JsonNode recipient = get("/campaigns/" + id + "/recipients", admin).body().get(0);
        String code = recipient.get("code").asString();
        assertThat(code).startsWith("THX-");
        assertThat(recipient.get("message").asString()).isEqualTo("Hi Big, thank you! Use " + code + " for 15% off at "
                + get("/store", admin).body().get("name").asString() + ".");

        Res csv = send("GET", "/campaigns/" + id + "/recipients.csv", null, admin);
        assertThat(csv.text()).startsWith("﻿Name,Phone,Email,Code,Message").contains(code).contains("big@example.com");

        // The code is the member's alone, and works once.
        assertThat(send("POST", "/orders", sale(once, p, 1, code), cashier).status()).isEqualTo(400);
        JsonNode used = sold(sale(big, p, 1, code));
        assertThat(dec(used.get("orderPromoDiscount"))).isPositive();

        JsonNode result = find(get("/campaigns", admin).body(), id);
        assertThat(result.get("codesUsed").asLong()).isEqualTo(1);
        assertThat(result.get("sales").asLong()).isEqualTo(1);
        assertThat(dec(result.get("revenue"))).isEqualByComparingTo(dec(used.get("total")));
    }

    @Test
    void permissions() {
        String accountant = login("accountant", "accountant1234");
        assertThat(get("/segments", accountant).status()).isEqualTo(200);
        assertThat(get("/campaigns", accountant).status()).isEqualTo(200);
        assertThat(send("POST", "/segments", Map.of("name", "x", "rules", Map.of()), accountant).status()).isEqualTo(403);
        assertThat(get("/segments", cashier).status()).isEqualTo(403);
        long segment = send("POST", "/segments", Map.of("name", "Everyone", "rules", Map.of()), admin).body().get("id").asLong();
        // Contact details leave the system only with "Manage campaigns".
        assertThat(send("GET", "/segments/" + segment + "/members.csv", null, accountant).status()).isEqualTo(403);
        assertThat(send("GET", "/segments/" + segment + "/members.csv", null, admin).status()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- helpers

    private long customer(String name, String email) {
        String phone = "08" + (10_000_000 + Math.floorMod(System.nanoTime(), 89_999_999));
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "phone", phone));
        if (email != null) {
            body.put("email", email);
        }
        Res r = send("POST", "/customers", body, admin);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    private JsonNode stocked(int n) {
        List<JsonNode> withStock = new ArrayList<>();
        get("/offerings?type=PRODUCT&forSale=true&size=200", admin).body().get("items").forEach(o -> {
            if (o.get("stock").asInt() >= 15 && dec(o.get("price")).signum() > 0) {
                withStock.add(o);
            }
        });
        return withStock.get(n);
    }

    private static Map<String, Object> sale(long customer, JsonNode p, int qty, String coupon) {
        Map<String, Object> m = new HashMap<>();
        m.put("items", List.of(Map.of("offeringId", p.get("id").asLong(), "quantity", qty)));
        m.put("paymentMethod", "CASH");
        m.put("customerId", customer);
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

    private static List<Long> ids(JsonNode list) {
        List<Long> out = new ArrayList<>();
        list.forEach(m -> out.add(m.get("id").asLong()));
        return out;
    }

    private static JsonNode find(JsonNode list, long id) {
        for (JsonNode x : list) {
            if (x.get("id").asLong() == id) {
                return x;
            }
        }
        throw new AssertionError("Not listed: " + id);
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
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            JsonNode json = null;
            if (bytes.length > 0 && (text.startsWith("{") || text.startsWith("["))) {
                json = JSON.readTree(bytes);
            }
            return new Res(res.getStatusCode().value(), json, text);
        });
    }
}
