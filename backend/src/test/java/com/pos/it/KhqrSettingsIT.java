package com.pos.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

/** Settings > KHQR payments through the API: saving, secrecy of the token, validation, roles, test. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KhqrSettingsIT {

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

    record Res(int status, JsonNode body) {
    }

    private static final String TOKEN = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8))
            + "." + Base64.getUrlEncoder().withoutPadding().encodeToString("{\"exp\":1900000000}".getBytes(StandardCharsets.UTF_8))
            + ".SIGNATURE-wxyz";

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api").build();
        admin = send("POST", "/auth/login", Map.of("username", "admin", "password", "admin1234"), null).body().get("accessToken").asString();
        manager = send("POST", "/auth/login", Map.of("username", "manager", "password", "manager1234"), null).body().get("accessToken").asString();
    }

    private Map<String, Object> body(String mode, String token, boolean remove) {
        var m = new HashMap<String, Object>(Map.of("mode", mode, "accountId", "shop@aclb", "merchantName", "My Shop",
                "merchantCity", "Phnom Penh", "apiBaseUrl", "https://sit-api-bakong.nbc.gov.kh/", "expiryMinutes", 10,
                "removeToken", remove));
        if (token != null) {
            m.put("apiToken", token);
        }
        return m;
    }

    @Test
    void savesSettingsAndKeepsTheTokenSecret() {
        JsonNode initial = send("GET", "/settings/khqr", null, manager).body();
        assertThat(initial.get("mode").asString()).isEqualTo("SIMULATED");
        assertThat(initial.get("tokenSource").asString()).isEqualTo("NONE");

        // Live mode needs a token.
        assertThat(send("PUT", "/settings/khqr", body("BAKONG", null, false), admin).status()).isEqualTo(400);

        Res saved = send("PUT", "/settings/khqr", body("BAKONG", "Bearer " + TOKEN, false), admin);
        assertThat(saved.status()).as(String.valueOf(saved.body())).isEqualTo(200);
        JsonNode s = saved.body();
        assertThat(s.get("mode").asString()).isEqualTo("BAKONG");
        assertThat(s.get("apiBaseUrl").asString()).isEqualTo("https://sit-api-bakong.nbc.gov.kh");
        assertThat(s.get("expiryMinutes").asInt()).isEqualTo(10);
        assertThat(s.get("tokenSource").asString()).isEqualTo("SAVED");
        assertThat(s.get("tokenHint").asString()).isEqualTo("wxyz");
        assertThat(s.get("tokenExpiresAt").asString()).startsWith("2030-03-17");
        assertThat(s.toString()).doesNotContain("SIGNATURE");
        assertThat(send("GET", "/settings/khqr", null, manager).body().toString()).doesNotContain("SIGNATURE");
        // Live mode now shows on every screen.
        assertThat(send("GET", "/store", null, manager).body().get("khqrSimulated").asBoolean()).isFalse();

        // Stored encrypted.
        byte[] raw = jdbc.sql("SELECT khqr_api_token FROM company_settings").query(byte[].class).single();
        assertThat(new String(raw, StandardCharsets.ISO_8859_1)).doesNotContain("SIGNATURE");

        // Saving without a token keeps it; the audit log never holds it.
        assertThat(send("PUT", "/settings/khqr", body("BAKONG", "", false), admin).body().get("tokenHint").asString()).isEqualTo("wxyz");
        String audit = jdbc.sql("SELECT GROUP_CONCAT(summary, COALESCE(details, '')) FROM audit_logs WHERE entity_id = 'khqr'")
                .query(String.class).single();
        assertThat(audit).contains("Bakong API token").doesNotContain("SIGNATURE");

        // Can't remove the token while live; can after switching back to test mode.
        assertThat(send("PUT", "/settings/khqr", body("BAKONG", null, true), admin).status()).isEqualTo(400);
        JsonNode removed = send("PUT", "/settings/khqr", body("SIMULATED", null, true), admin).body();
        assertThat(removed.get("tokenSource").asString()).isEqualTo("NONE");
        assertThat(send("GET", "/store", null, manager).body().get("khqrSimulated").asBoolean()).isTrue();
    }

    @Test
    void validatesAndRestrictsChanges() {
        assertThat(send("PUT", "/settings/khqr", body("SIMULATED", null, false), manager).status()).isEqualTo(403);
        var badUrl = body("SIMULATED", null, false);
        badUrl.put("apiBaseUrl", "http://evil.example.com");
        assertThat(send("PUT", "/settings/khqr", badUrl, admin).status()).isEqualTo(400);
        var badAccount = body("SIMULATED", null, false);
        badAccount.put("accountId", "not an account");
        assertThat(send("PUT", "/settings/khqr", badAccount, admin).status()).isEqualTo(400);
        var badExpiry = body("SIMULATED", null, false);
        badExpiry.put("expiryMinutes", 0);
        assertThat(send("PUT", "/settings/khqr", badExpiry, admin).status()).isEqualTo(400);
        assertThat(send("PUT", "/settings/khqr", body("SIMULATED", "short", false), admin).status()).isEqualTo(400);
        assertThat(send("POST", "/settings/khqr/test", Map.of("apiToken", TOKEN), manager).status()).isEqualTo(403);
    }

    @Test
    void connectionTestReportsARefusedToken() {
        // Point the test at this server, which refuses the token just like Bakong would.
        Res r = send("POST", "/settings/khqr/test", Map.of("apiBaseUrl", "http://localhost:" + port + "/api", "apiToken", TOKEN), admin);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("status").asString()).isEqualTo("UNAUTHORIZED");
        Res none = send("POST", "/settings/khqr/test", Map.of("apiBaseUrl", "http://localhost:1"), admin);
        assertThat(none.status()).isIn(200, 400);
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
