package com.pos.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.pos.hold.HeldSaleService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Held sales: holding, resuming with a lock, take-over, voiding, expiry, and completion at checkout. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HeldSalesIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    HeldSaleService holds;

    RestClient http;
    String admin;
    String cashier;
    String manager;

    record Res(int status, JsonNode body) {
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api").build();
        admin = login("admin", "admin1234");
        cashier = login("cashier", "cashier1234");
        manager = login("manager", "manager1234");
    }

    @Test
    void holdResumeAndCheckout() {
        JsonNode p = product(5);
        JsonNode h = hold(p, 2, "Table 7", cashier);
        assertThat(h.get("holdNumber").asString()).matches("HOLD-\\d{5}");
        assertThat(h.get("status").asString()).isEqualTo("HELD");
        assertThat(h.get("itemCount").asInt()).isEqualTo(2);
        assertThat(h.get("expiresAt").isNull()).isFalse();
        long id = h.get("id").asLong();
        // Every till in the branch sees it.
        assertThat(ids(get("/held-sales", manager).body())).contains(id);
        assertThat(get("/held-sales/count", cashier).body().get("open").asLong()).isPositive();

        // Resuming locks it; another cashier is refused, a manager can take over.
        Res resumed = send("POST", "/held-sales/" + id + "/resume", null, cashier);
        assertThat(resumed.status()).as(String.valueOf(resumed.body())).isEqualTo(200);
        assertThat(resumed.body().get("cart").get("lines").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(resumed.body().get("warnings")).isEmpty();
        Res busy = send("POST", "/held-sales/" + id + "/resume", null, manager);
        assertThat(busy.status()).isEqualTo(409);
        assertThat(busy.body().get("code").asString()).isEqualTo("HOLD_IN_USE");
        Res taken = send("POST", "/held-sales/" + id + "/resume?takeOver=true", null, manager);
        assertThat(taken.status()).isEqualTo(200);
        // The first cashier can no longer complete it.
        assertThat(send("POST", "/orders", sale(p, 2, id, null), cashier).status()).isEqualTo(409);

        // Paying completes the hold; it can't be used again.
        Res order = send("POST", "/orders", sale(p, 2, id, h.get("estimatedTotal").decimalValue()), manager);
        assertThat(order.status()).as(String.valueOf(order.body())).isEqualTo(201);
        JsonNode closed = jdbc.sql("SELECT status, order_id FROM held_sales WHERE id = ?").param(id)
                .query((rs, n) -> JSON.createObjectNode().put("status", rs.getString(1)).put("orderId", rs.getLong(2))).single();
        assertThat(closed.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(closed.get("orderId").asLong()).isEqualTo(order.body().get("id").asLong());
        assertThat(ids(get("/held-sales", cashier).body())).doesNotContain(id);
        assertThat(send("POST", "/held-sales/" + id + "/resume", null, manager).status()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE entity_type = 'HeldSale' AND entity_id = ?").param(id)
                .query(Long.class).single()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void priceChangesAreFlaggedAndGuarded() {
        JsonNode p = product(6);
        long offeringId = p.get("id").asLong();
        BigDecimal old = p.get("price").decimalValue();
        JsonNode h = hold(p, 1, null, cashier);
        jdbc.sql("UPDATE offerings SET price = price + 1 WHERE id = ?").param(offeringId).update();
        try {
            JsonNode resumed = send("POST", "/held-sales/" + h.get("id").asLong() + "/resume", null, cashier).body();
            assertThat(resumed.get("warnings").get(0).asString()).contains("price changed from " + old);
            // Charging the total shown at hold time is refused now that prices moved.
            Res stale = send("POST", "/orders", sale(p, 1, h.get("id").asLong(), h.get("estimatedTotal").decimalValue()), cashier);
            assertThat(stale.status()).isEqualTo(409);
            assertThat(stale.body().get("code").asString()).isEqualTo("PRICES_CHANGED");
            assertThat(jdbc.sql("SELECT status FROM held_sales WHERE id = ?").param(h.get("id").asLong()).query(String.class).single())
                    .isEqualTo("IN_USE");
            // Putting it back makes it available to everyone again.
            assertThat(send("POST", "/held-sales/" + h.get("id").asLong() + "/put-back", null, cashier).body().get("status").asString())
                    .isEqualTo("HELD");
        } finally {
            jdbc.sql("UPDATE offerings SET price = price - 1 WHERE id = ?").param(offeringId).update();
        }
    }

    @Test
    void voidingNeedsAReasonAndTheRightPerson() {
        JsonNode p = product(7);
        long id = hold(p, 1, "Phone order", manager).get("id").asLong();
        // A cashier can't void someone else's hold.
        assertThat(send("POST", "/held-sales/" + id + "/void", Map.of("reason", "Customer left"), cashier).status()).isEqualTo(403);
        long mine = hold(p, 1, null, cashier).get("id").asLong();
        assertThat(send("POST", "/held-sales/" + mine + "/void", Map.of("reason", ""), cashier).status()).isEqualTo(400);
        Res voided = send("POST", "/held-sales/" + mine + "/void", Map.of("reason", "Customer left"), cashier);
        assertThat(voided.status()).isEqualTo(200);
        assertThat(voided.body().get("status").asString()).isEqualTo("VOIDED");
        assertThat(ids(get("/held-sales?closed=true", cashier).body())).contains(mine);
        // A manager can void anyone's.
        assertThat(send("POST", "/held-sales/" + id + "/void", Map.of("reason", "Duplicate"), manager).status()).isEqualTo(200);
        // Only sellers can hold.
        assertThat(send("POST", "/held-sales", holdBody(p, 1, null), login("stock", "stock12345")).status()).isEqualTo(403);
    }

    @Test
    void oldHoldsExpireAndOnlyManagersRestoreThem() {
        JsonNode p = product(8);
        long id = hold(p, 1, null, cashier).get("id").asLong();
        jdbc.sql("UPDATE held_sales SET updated_at = NOW() - INTERVAL 2 DAY WHERE id = ?").param(id).update();
        assertThat(holds.housekeeping()).isPositive();
        assertThat(jdbc.sql("SELECT status FROM held_sales WHERE id = ?").param(id).query(String.class).single()).isEqualTo("EXPIRED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'HELD_SALE_EXPIRED' AND entity_id = ?").param(id)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(send("POST", "/held-sales/" + id + "/resume", null, cashier).status()).isEqualTo(409);
        assertThat(send("POST", "/held-sales/" + id + "/resume", null, manager).status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT closed_at IS NULL FROM held_sales WHERE id = ?").param(id).query(Boolean.class).single()).isTrue();

        // An abandoned lock is released after the lock timeout.
        jdbc.sql("UPDATE held_sales SET locked_at = NOW() - INTERVAL 2 HOUR WHERE id = ?").param(id).update();
        holds.housekeeping();
        assertThat(jdbc.sql("SELECT status FROM held_sales WHERE id = ?").param(id).query(String.class).single()).isEqualTo("HELD");
    }

    @Test
    void aHoldWaitingForKhqrCannotBeChargedAgain() {
        JsonNode p = product(9);
        long id = hold(p, 1, null, cashier).get("id").asLong();
        assertThat(send("POST", "/held-sales/" + id + "/resume", null, cashier).status()).isEqualTo(200);
        var khqrSale = sale(p, 1, id, null);
        khqrSale.put("paymentMethod", "KHQR");
        Res pending = send("POST", "/orders", khqrSale, cashier);
        assertThat(pending.status()).as(String.valueOf(pending.body())).isEqualTo(201);
        assertThat(pending.body().get("status").asString()).isEqualTo("PENDING_PAYMENT");

        // Same till (e.g. after a page reload): charging cash for the same hold is refused.
        Res again = send("POST", "/orders", sale(p, 1, id, null), cashier);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().get("code").asString()).isEqualTo("HOLD_AWAITING_PAYMENT");

        // Another till, after the lock has gone stale: still refused, even for a manager taking over.
        jdbc.sql("UPDATE held_sales SET locked_at = NOW() - INTERVAL 2 HOUR WHERE id = ?").param(id).update();
        Res other = send("POST", "/held-sales/" + id + "/resume?takeOver=true", null, manager);
        assertThat(other.status()).isEqualTo(409);
        assertThat(other.body().get("code").asString()).isEqualTo("HOLD_AWAITING_PAYMENT");
        holds.housekeeping();
        assertThat(jdbc.sql("SELECT status FROM held_sales WHERE id = ?").param(id).query(String.class).single()).isEqualTo("IN_USE");

        // The KHQR payment completes the hold with the first order.
        long paymentId = pending.body().get("payments").get(0).get("id").asLong();
        assertThat(send("POST", "/payments/" + paymentId + "/simulate-paid", null, cashier).status()).isEqualTo(200);
        JsonNode closed = jdbc.sql("SELECT status, order_id FROM held_sales WHERE id = ?").param(id)
                .query((rs, n) -> JSON.createObjectNode().put("status", rs.getString(1)).put("orderId", rs.getLong(2))).single();
        assertThat(closed.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(closed.get("orderId").asLong()).isEqualTo(pending.body().get("id").asLong());
        assertThat(ids(get("/held-sales?closed=true", cashier).body())).contains(id);
    }

    private JsonNode hold(JsonNode p, int qty, String label, String token) {
        Res r = send("POST", "/held-sales", holdBody(p, qty, label), token);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return r.body();
    }

    private static Map<String, Object> holdBody(JsonNode p, int qty, String label) {
        var m = new HashMap<String, Object>(Map.of("lines", List.of(Map.of("offeringId", p.get("id").asLong(), "quantity", qty))));
        if (label != null) {
            m.put("label", label);
        }
        return m;
    }

    private static Map<String, Object> sale(JsonNode p, int qty, Long heldSaleId, BigDecimal expectedTotal) {
        var m = new HashMap<String, Object>(Map.of("items", List.of(Map.of("offeringId", p.get("id").asLong(), "quantity", qty)),
                "paymentMethod", "CASH"));
        if (heldSaleId != null) {
            m.put("heldSaleId", heldSaleId);
        }
        if (expectedTotal != null) {
            m.put("expectedTotal", expectedTotal);
        }
        return m;
    }

    private JsonNode product(int index) {
        return get("/offerings?type=PRODUCT&forSale=true&size=50", admin).body().get("items").get(index);
    }

    private static List<Long> ids(JsonNode list) {
        return list.valueStream().map(n -> n.get("id").asLong()).toList();
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
