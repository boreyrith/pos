package com.pos.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

/** Dashboard and Reports agree with each other and with what was sold, refunded and paid. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportsIT {

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
    void dashboardAndReportsMatchWhatWasSoldRefundedAndPaid() {
        String today = LocalDate.now(ZoneId.of("Asia/Phnom_Penh")).toString();
        JsonNode before = get("/reports/dashboard?days=1", admin).body();

        JsonNode p = product();
        BigDecimal cost = p.get("cost").decimalValue();
        JsonNode sale = send("POST", "/orders", Map.of("items", List.of(Map.of("offeringId", p.get("id").asLong(), "quantity", 4)),
                "paymentMethod", "CASH"), cashier).body();
        // The line keeps its cost at the time of sale.
        JsonNode refundAll = send("POST", "/orders", Map.of("items", List.of(Map.of("offeringId", p.get("id").asLong(), "quantity", 1)),
                "paymentMethod", "CASH"), cashier).body();
        send("POST", "/orders/" + refundAll.get("id").asLong() + "/refund", null, admin);
        JsonNode item = sale.get("items").get(0);
        send("POST", "/orders/" + sale.get("id").asLong() + "/returns",
                Map.of("lines", List.of(Map.of("orderItemId", item.get("id").asLong(), "quantity", 1))), admin);

        JsonNode after = get("/reports/dashboard?days=1", admin).body();
        BigDecimal soldTotal = dec(sale, "total").add(dec(refundAll, "total"));
        BigDecimal refunded = dec(refundAll, "total").add(dec(sale, "total").divide(BigDecimal.valueOf(4), 2, RoundingMode.HALF_UP));
        // Sales = paid minus refunded; both orders count; the refunded order's payment is still money taken.
        assertThat(delta(before, after, "today", "orders")).isEqualByComparingTo("2");
        assertThat(delta(before, after, "today", "refunds")).isEqualByComparingTo(refunded);
        assertThat(delta(before, after, "today", "sales")).isEqualByComparingTo(soldTotal.subtract(refunded));
        assertThat(delta(before, after, "today", "cost")).isEqualByComparingTo(cost.multiply(BigDecimal.valueOf(3)));
        BigDecimal paidBefore = sum(before.get("paymentMethods"), "amount");
        BigDecimal paidAfter = sum(after.get("paymentMethods"), "amount");
        assertThat(paidAfter.subtract(paidBefore)).isEqualByComparingTo(soldTotal);
        assertThat(after.get("refundsPaid").decimalValue().subtract(before.get("refundsPaid").decimalValue())).isEqualByComparingTo(refunded);

        // The sales summary agrees with the dashboard for the same day.
        JsonNode summary = get("/reports/run/sales-summary?from=" + today + "&to=" + today, admin).body();
        JsonNode totals = summary.get("totals");
        assertThat(totals.get("orders").asLong()).isEqualTo(after.get("today").get("orders").asLong());
        assertThat(totals.get("netAfterReturns").decimalValue()).isEqualByComparingTo(after.get("today").get("netSales").decimalValue());
        assertThat(totals.get("profit").decimalValue()).isEqualByComparingTo(after.get("today").get("profit").decimalValue());

        // Per item: 5 sold, 2 returned, 3 kept; cost of the 3 kept.
        JsonNode byItem = get("/reports/run/sales-by-item?from=" + today + "&to=" + today, admin).body();
        JsonNode row = null;
        for (JsonNode r : byItem.get("rows")) {
            if (r.get("sku").asString().equals(p.get("sku").asString())) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("sold").asLong()).isGreaterThanOrEqualTo(5);
        assertThat(row.get("returned").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(row.get("profit").decimalValue()).isEqualByComparingTo(row.get("netSales").decimalValue().subtract(row.get("cost").decimalValue()));

        // Every report runs, and exports to CSV.
        for (JsonNode info : get("/reports/catalog", admin).body()) {
            String key = info.get("key").asString();
            Res r = get("/reports/run/" + key + "?from=" + today + "&to=" + today, admin);
            assertThat(r.status()).as(key + " " + r.text()).isEqualTo(200);
            assertThat(r.body().get("columns").size()).as(key).isPositive();
            Res csv = send("GET", "/reports/run/" + key + ".csv?from=" + today + "&to=" + today, null, admin);
            assertThat(csv.status()).as(key + " csv").isEqualTo(200);
            assertThat(csv.text()).contains(r.body().get("columns").get(0).get("label").asString());
        }
        for (String group : List.of("week", "month")) {
            assertThat(get("/reports/run/sales-summary?groupBy=" + group, admin).status()).isEqualTo(200);
        }
        assertThat(get("/reports/run/sales-by-time?groupBy=weekday", admin).body().get("rows").size()).isEqualTo(7);
        assertThat(get("/reports/run/sales-by-time", admin).body().get("rows").size()).isEqualTo(24);
    }

    @Test
    void reportsFollowPermissions() {
        assertThat(get("/reports/catalog", cashier).body().size()).isZero();
        assertThat(get("/reports/run/sales-summary", cashier).status()).isEqualTo(403);
        assertThat(get("/reports/dashboard", cashier).status()).isEqualTo(403);
        String stock = login("stock", "stock12345");
        List<String> keys = new ArrayList<>();
        get("/reports/catalog", stock).body().forEach(i -> keys.add(i.get("key").asString()));
        assertThat(keys).containsExactlyInAnyOrder("inventory-valuation", "stock-movements");
        assertThat(get("/reports/run/inventory-valuation", stock).status()).isEqualTo(200);
        assertThat(get("/reports/run/sales-by-item", stock).status()).isEqualTo(403);
        assertThat(get("/reports/run/nonsense", admin).status()).isEqualTo(404);
        assertThat(get("/reports/run/sales-summary?from=2026-02-01&to=2026-01-01", admin).status()).isEqualTo(400);
        // Managers only see their own branch, even if they ask for another.
        String manager = login("manager", "manager1234");
        assertThat(get("/reports/dashboard?branchId=999", manager).status()).isEqualTo(200);
    }

    private BigDecimal delta(JsonNode before, JsonNode after, String section, String field) {
        return after.get(section).get(field).decimalValue().subtract(before.get(section).get(field).decimalValue());
    }

    private static BigDecimal sum(JsonNode arr, String field) {
        BigDecimal s = BigDecimal.ZERO;
        for (JsonNode n : arr) {
            s = s.add(n.get(field).decimalValue());
        }
        return s;
    }

    private JsonNode product() {
        for (JsonNode o : get("/offerings?type=PRODUCT&size=100", admin).body().get("items")) {
            if (o.get("stock").asInt() >= 20 && o.get("cost").decimalValue().signum() > 0) {
                return o;
            }
        }
        throw new AssertionError("product");
    }

    private static BigDecimal dec(JsonNode n, String f) {
        return n.get(f).decimalValue();
    }

    private String login(String user, String password) {
        return send("POST", "/auth/login", Map.of("username", user, "password", password), null, null).body().get("accessToken").asString();
    }

    private Res get(String path, String token) {
        return send("GET", path, null, token);
    }

    private Res send(String method, String path, Object body, String token) {
        return send(method, path, body, token, null);
    }

    private Res send(String method, String path, Object body, String token, Object unused) {
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
            try {
                json = bytes.length == 0 ? null : JSON.readTree(bytes);
            } catch (RuntimeException e) {
                // CSV
            }
            return new Res(res.getStatusCode().value(), json, text);
        });
    }
}
