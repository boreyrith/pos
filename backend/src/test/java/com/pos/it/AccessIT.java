package com.pos.it;

import static org.assertj.core.api.Assertions.assertThat;

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

/** Roles and permissions end to end: built-in roles, custom roles, immediate effect, escalation rules. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    RestClient http;
    String admin;

    record Res(int status, JsonNode body) {
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api").build();
        admin = login("admin", "admin1234");
    }

    @Test
    void builtInRolesGetTheirModules() {
        String cashier = login("cashier", "cashier1234");
        String manager = login("manager", "manager1234");
        String stock = login("stock", "stock12345");
        String accountant = login("accountant", "accountant1234");

        JsonNode me = get("/auth/me", accountant).body();
        assertThat(me.get("role").get("code").asString()).isEqualTo("ACCOUNTANT");
        assertThat(texts(me.get("permissions"))).contains("REPORTS_TAX", "ORDERS_VIEW").doesNotContain("SALES_SELL");
        assertThat(get("/auth/me", admin).body().get("role").get("superAdmin").asBoolean()).isTrue();

        // Cashier: sells and sees orders, nothing else.
        assertThat(get("/orders", cashier).status()).isEqualTo(200);
        assertThat(get("/reports/dashboard", cashier).status()).isEqualTo(403);
        assertThat(get("/stock-movements", cashier).status()).isEqualTo(403);
        assertThat(get("/users", cashier).status()).isEqualTo(403);

        // Inventory staff: stock, not sales.
        assertThat(get("/stock-movements", stock).status()).isEqualTo(200);
        assertThat(get("/stock-adjustments", stock).status()).isEqualTo(200);
        assertThat(get("/orders", stock).status()).isEqualTo(403);
        assertThat(send("POST", "/orders", Map.of("items", List.of(Map.of("offeringId", 1, "quantity", 1)), "paymentMethod", "CASH"), stock).status())
                .isEqualTo(403);

        // Accountant: reports and transactions across branches, read-only.
        assertThat(get("/reports/dashboard", accountant).status()).isEqualTo(200);
        assertThat(get("/orders", accountant).status()).isEqualTo(200);
        assertThat(get("/settings/tax", accountant).status()).isEqualTo(200);
        assertThat(send("POST", "/orders", Map.of("items", List.of(Map.of("offeringId", 1, "quantity", 1)), "paymentMethod", "CASH"), accountant).status())
                .isEqualTo(403);
        assertThat(send("POST", "/categories", Map.of("name", "X"), accountant).status()).isEqualTo(403);

        // Manager: sees users of their branch only, can't change them.
        List<String> seen = new ArrayList<>();
        get("/users", manager).body().forEach(u -> seen.add(u.get("username").asString()));
        assertThat(seen).contains("cashier").doesNotContain("srmanager");
        assertThat(send("POST", "/users", newUser("mgrmade1", role("CASHIER")), manager).status()).isEqualTo(403);
        assertThat(get("/audit-logs", manager).status()).isEqualTo(403);
    }

    @Test
    void customRolesApplyImmediately() {
        Res created = send("POST", "/roles", Map.of("name", "Night cashier " + System.nanoTime(), "description", "Evening shift",
                "permissions", List.of("SALES_SELL", "ORDERS_REFUND")), admin);
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        long roleId = created.body().get("id").asLong();
        // Refunds need seeing orders: added automatically.
        assertThat(texts(created.body().get("permissions"))).contains("ORDERS_VIEW");
        assertThat(created.body().get("code").asString()).startsWith("CUSTOM_NIGHT_CASHIER");

        String username = "night" + (System.nanoTime() % 100000);
        Res user = send("POST", "/users", newUser(username, roleId), admin);
        assertThat(user.status()).as(String.valueOf(user.body())).isEqualTo(201);
        String night = login(username, "password123");
        assertThat(get("/reports/dashboard", night).status()).isEqualTo(403);

        // Grant the dashboard: the same session can use it straight away.
        JsonNode r = get("/roles", admin).body();
        String name = null;
        for (JsonNode x : r) {
            if (x.get("id").asLong() == roleId) {
                name = x.get("name").asString();
            }
        }
        assertThat(send("PUT", "/roles/" + roleId, Map.of("name", name, "permissions", List.of("SALES_SELL", "ORDERS_REFUND", "REPORTS_DASHBOARD")), admin).status())
                .isEqualTo(200);
        assertThat(get("/reports/dashboard", night).status()).isEqualTo(200);

        // Can't delete a role in use; can after reassigning.
        assertThat(send("DELETE", "/roles/" + roleId, null, admin).status()).isEqualTo(409);
        long userId = user.body().get("id").asLong();
        assertThat(send("PUT", "/users/" + userId, Map.of("fullName", "Night", "roleId", role("CASHIER"), "active", true, "branchId", 1), admin).status())
                .isEqualTo(200);
        assertThat(get("/reports/dashboard", night).status()).isEqualTo(403);
        assertThat(send("DELETE", "/roles/" + roleId, null, admin).status()).isEqualTo(204);

        // Disabling the account ends its access at once.
        assertThat(send("PUT", "/users/" + userId, Map.of("fullName", "Night", "roleId", role("CASHIER"), "active", false, "branchId", 1), admin).status())
                .isEqualTo(200);
        assertThat(get("/orders", night).status()).isEqualTo(401);
    }

    @Test
    void nobodyCanGrantMoreThanTheyHave() {
        // An Admin (all permissions except managing roles).
        String username = "ops" + (System.nanoTime() % 100000);
        assertThat(send("POST", "/users", newUser(username, role("ADMIN")), admin).status()).isEqualTo(201);
        String ops = login(username, "password123");

        assertThat(send("POST", "/users", newUser("sa" + username, role("SUPER_ADMIN")), ops).status()).isEqualTo(403);
        assertThat(send("POST", "/users", newUser("c" + username, role("CASHIER")), ops).status()).isEqualTo(201);
        assertThat(send("POST", "/roles", Map.of("name", "Sneaky", "permissions", List.of("SALES_SELL")), ops).status()).isEqualTo(403);
        long adminId = get("/auth/me", admin).body().get("id").asLong();
        assertThat(send("POST", "/users/" + adminId + "/reset-password", Map.of("password", "hijacked123"), ops).status()).isEqualTo(403);
        long opsId = get("/auth/me", ops).body().get("id").asLong();
        assertThat(send("PUT", "/users/" + opsId, Map.of("fullName", "Ops", "roleId", role("CASHIER"), "active", true, "branchId", 1), ops).status())
                .isEqualTo(400);

        // Built-in roles: Super Admin can't be edited, none can be deleted or renamed.
        assertThat(send("PUT", "/roles/" + role("SUPER_ADMIN"), Map.of("name", "Super Admin", "permissions", List.of()), admin).status()).isEqualTo(400);
        assertThat(send("DELETE", "/roles/" + role("CASHIER"), null, admin).status()).isEqualTo(400);
        assertThat(send("PUT", "/roles/" + role("CASHIER"), Map.of("name", "Till", "permissions", List.of("SALES_SELL")), admin).status()).isEqualTo(400);

        // The only Super Admin can't lose the role.
        assertThat(send("PUT", "/users/" + adminId, Map.of("fullName", "Admin", "roleId", role("ADMIN"), "active", true, "branchId", 1), admin).status())
                .isEqualTo(400);
        // Permission catalog is available for the editor.
        assertThat(get("/roles/permissions", admin).body().size()).isGreaterThan(20);
    }

    @Test
    void roleManagersCanOnlyAllowDiscountsUpToTheirOwnLimit() {
        Map<String, Object> supervisor = new HashMap<>(Map.of("name", "Supervisor " + System.nanoTime(),
                "permissions", List.of("SALES_SELL", "DISCOUNT_ITEMS", "ROLES_MANAGE"), "maxItemDiscountPct", 10));
        Res role = send("POST", "/roles", supervisor, admin);
        assertThat(role.status()).as(String.valueOf(role.body())).isEqualTo(201);
        assertThat(role.body().get("maxItemDiscountPct").decimalValue()).isEqualByComparingTo("10");
        String username = "sup" + (System.nanoTime() % 100000);
        assertThat(send("POST", "/users", newUser(username, role.body().get("id").asLong()), admin).status()).isEqualTo(201);
        String sup = login(username, "password123");
        assertThat(get("/auth/me", sup).body().get("role").get("maxItemDiscountPct").decimalValue()).isEqualByComparingTo("10");

        Map<String, Object> unlimited = new HashMap<>();
        unlimited.put("name", "Till " + System.nanoTime());
        unlimited.put("permissions", List.of("SALES_SELL", "DISCOUNT_ITEMS"));
        unlimited.put("maxItemDiscountPct", null);
        assertThat(send("POST", "/roles", unlimited, sup).status()).isEqualTo(403);
        unlimited.put("maxItemDiscountPct", 15);
        assertThat(send("POST", "/roles", unlimited, sup).status()).isEqualTo(403);
        unlimited.put("maxItemDiscountPct", 5);
        assertThat(send("POST", "/roles", unlimited, sup).status()).isEqualTo(201);
    }

    private Map<String, Object> newUser(String username, long roleId) {
        var m = new HashMap<String, Object>(Map.of("username", username, "password", "password123", "fullName", "Test " + username,
                "roleId", roleId, "branchId", 1, "mustChangePassword", false));
        return m;
    }

    private long role(String code) {
        for (JsonNode r : get("/roles", admin).body()) {
            if (r.get("code").asString().equals(code)) {
                return r.get("id").asLong();
            }
        }
        throw new AssertionError(code);
    }

    private static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asString()));
        return out;
    }

    private String login(String user, String password) {
        Res r = send("POST", "/auth/login", Map.of("username", user, "password", password), null);
        assertThat(r.status()).as(user + " " + r.body()).isEqualTo(200);
        return r.body().get("accessToken").asString();
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
