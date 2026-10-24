package com.pos.it;

import static org.assertj.core.api.Assertions.assertThat;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Customers: the default Walk-in Customer, sales to customers, lookups, history and permissions. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomerIT {

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
    String cashier;
    String stock;

    record Res(int status, JsonNode body) {
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api").build();
        admin = login("admin", "admin1234");
        cashier = login("cashier", "cashier1234");
        stock = login("stock", "stock12345");
    }

    @Test
    void salesDefaultToTheWalkInCustomer() {
        JsonNode walkIn = get("/customers/walk-in", cashier).body();
        assertThat(walkIn.get("walkIn").asBoolean()).isTrue();
        assertThat(walkIn.get("code").asString()).isEqualTo("WALK-IN");

        JsonNode o = sell(null, "Table 4 guest");
        assertThat(o.get("customerId").asLong()).isEqualTo(walkIn.get("id").asLong());
        assertThat(o.get("walkIn").asBoolean()).isTrue();
        assertThat(o.get("customerName").asString()).isEqualTo("Table 4 guest");

        // It can't be switched off.
        Map<String, Object> off = new HashMap<>(Map.of("name", "Walk-in Customer", "active", false));
        assertThat(send("PUT", "/customers/" + walkIn.get("id").asLong(), off, admin).status()).isEqualTo(400);
        // Customers search lists it first.
        assertThat(get("/customers?q=", cashier).body().get("items").get(0).get("walkIn").asBoolean()).isTrue();
    }

    @Test
    void cashiersAddCustomersAndSellToThem() {
        String phone = "012 " + (System.nanoTime() % 1000) + " " + (100 + System.nanoTime() % 900);
        Res created = send("POST", "/customers", Map.of("name", "Sokha Chan", "phone", phone, "taxId", "K001-123"), cashier);
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        JsonNode c = created.body();
        assertThat(c.get("code").asString()).startsWith("CUS-");
        String digits = phone.replace(" ", "");
        assertThat(c.get("phone").asString()).isEqualTo(digits);
        // The same number, written differently, is a duplicate.
        assertThat(send("POST", "/customers", Map.of("name", "Someone else", "phone", digits.substring(0, 3) + "-" + digits.substring(3)), cashier).status())
                .isEqualTo(409);
        // Found by phone in any format, and by name.
        assertThat(get("/customers?q=" + digits.substring(0, 6), cashier).body().get("items").get(0).get("id").asLong()).isEqualTo(c.get("id").asLong());
        assertThat(get("/customers?q=Sokha", cashier).body().get("items").size()).isPositive();

        JsonNode o = sell(c.get("id").asLong(), "ignored for named customers");
        assertThat(o.get("walkIn").asBoolean()).isFalse();
        assertThat(o.get("customerName").asString()).isEqualTo("Sokha Chan");
        assertThat(o.get("customerTaxId").asString()).isEqualTo("K001-123");

        // History and totals.
        JsonNode detail = get("/customers/" + c.get("id").asLong(), cashier).body();
        assertThat(detail.get("stats").get("orders").asLong()).isEqualTo(1);
        assertThat(detail.get("stats").get("spent").decimalValue()).isEqualByComparingTo(o.get("total").decimalValue());
        JsonNode orders = get("/orders?customerId=" + c.get("id").asLong(), admin).body().get("items");
        assertThat(orders).hasSize(1);

        // A deactivated customer can't be sold to; renaming keeps old orders' name.
        Map<String, Object> rename = new HashMap<>(Map.of("name", "Sokha C.", "phone", digits, "active", false));
        assertThat(send("PUT", "/customers/" + c.get("id").asLong(), rename, admin).status()).isEqualTo(200);
        assertThat(send("POST", "/orders", order(c.get("id").asLong(), null), cashier).status()).isEqualTo(400);
        assertThat(get("/orders/" + o.get("id").asLong(), admin).body().get("customerName").asString()).isEqualTo("Sokha Chan");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action IN ('CUSTOMER_CREATED', 'CUSTOMER_UPDATED')").query(Long.class).single())
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void permissions() {
        // Inventory staff don't sell and can't see customers.
        assertThat(get("/customers", stock).status()).isEqualTo(403);
        assertThat(send("POST", "/customers", Map.of("name", "X"), stock).status()).isEqualTo(403);
        String accountant = login("accountant", "accountant1234");
        assertThat(get("/customers", accountant).status()).isEqualTo(200);
        assertThat(send("POST", "/customers", Map.of("name", "X"), accountant).status()).isEqualTo(403);
    }

    private JsonNode sell(Long customerId, String name) {
        Res r = send("POST", "/orders", order(customerId, name), cashier);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return r.body();
    }

    private Map<String, Object> order(Long customerId, String name) {
        JsonNode p = get("/offerings?type=PRODUCT&size=50", admin).body().get("items").get(3);
        var m = new HashMap<String, Object>(Map.of("items", List.of(Map.of("offeringId", p.get("id").asLong(), "quantity", 1)), "paymentMethod", "CASH"));
        if (customerId != null) {
            m.put("customerId", customerId);
        }
        if (name != null) {
            m.put("customerName", name);
        }
        return m;
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
