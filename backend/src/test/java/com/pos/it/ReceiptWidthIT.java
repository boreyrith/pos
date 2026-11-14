package com.pos.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
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

/** Settings > Company > Receipt paper: 80mm by default, 58mm on request, shown to every screen, audited. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReceiptWidthIT {

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

    @Test
    void storesChoosePaperWidth() {
        assertThat(send("GET", "/store", null, cashier).body().get("receiptWidth").asInt()).isEqualTo(80);
        assertThat(send("GET", "/settings/company", null, manager).body().get("receiptWidth").asInt()).isEqualTo(80);

        // Only those who may change company settings; only the widths the apps can print.
        assertThat(send("PUT", "/settings/company", body(58), manager).status()).isEqualTo(403);
        assertThat(send("PUT", "/settings/company", body(50), admin).status()).isEqualTo(400);
        assertThat(send("PUT", "/settings/company", body(null), admin).status()).isEqualTo(400);

        Res saved = send("PUT", "/settings/company", body(58), admin);
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.body().get("receiptWidth").asInt()).isEqualTo(58);
        // Every till sees it at once (the settings are cached).
        assertThat(send("GET", "/store", null, cashier).body().get("receiptWidth").asInt()).isEqualTo(58);
        String audit = jdbc.sql("SELECT CONCAT(summary, COALESCE(details, '')) FROM audit_logs WHERE entity_id = 'company' ORDER BY id DESC LIMIT 1")
                .query(String.class).single();
        assertThat(audit).contains("Receipt paper").contains("80mm").contains("58mm");

        assertThat(send("PUT", "/settings/company", body(80), admin).body().get("receiptWidth").asInt()).isEqualTo(80);
    }

    /** The current company settings with the given paper width. */
    private Map<String, Object> body(Integer width) {
        JsonNode c = send("GET", "/settings/company", null, admin).body();
        var m = new HashMap<String, Object>();
        m.put("name", c.get("name").asString());
        m.put("timezone", c.get("timezone").asString());
        m.put("receiptFooter", c.get("receiptFooter").isNull() ? null : c.get("receiptFooter").asString());
        m.put("receiptWidth", width);
        return m;
    }

    private String login(String username, String password) {
        return send("POST", "/auth/login", Map.of("username", username, "password", password), null).body().get("accessToken").asString();
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
