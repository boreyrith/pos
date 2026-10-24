package com.pos.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Refresh-token rotation: a token repeated right after it was replaced (the browser lost the
 * response) keeps the session; a token repeated later, or after signing out, is treated as theft.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RefreshTokenIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    RestClient http;

    /** status, the new refresh cookie (null if none), the body. */
    record Res(int status, String cookie, JsonNode body) {
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api").build();
    }

    @Test
    void lostResponseWithinGraceKeepsTheSession() {
        String first = login("manager", "manager1234");
        Res rotated = refresh(first);
        assertThat(rotated.status()).isEqualTo(200);
        String second = rotated.cookie();
        assertThat(second).isNotEqualTo(first);

        // The browser never got `second` (reload mid-refresh) and sends `first` again: not theft.
        Res again = refresh(first);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("user").get("username").asString()).isEqualTo("manager");
        assertThat(refresh(again.cookie()).status()).isEqualTo(200);
        assertThat(reuseAlerts("manager")).isZero();
    }

    @Test
    void reuseAfterTheGraceSignsOutEverywhere() {
        String first = login("cashier", "cashier1234");
        String second = refresh(first).cookie();
        // Pretend the rotation was a minute ago.
        jdbc.sql("UPDATE refresh_tokens SET rotated_at = rotated_at - INTERVAL 60 SECOND WHERE rotated_at IS NOT NULL").update();

        assertThat(refresh(first).status()).isEqualTo(401);
        // Every session of that user is gone, including the legitimate newer one.
        assertThat(refresh(second).status()).isEqualTo(401);
        assertThat(reuseAlerts("cashier")).isPositive();
    }

    @Test
    void signedOutTokensGetNoGrace() {
        String token = login("stock", "stock12345");
        Res out = send("POST", "/auth/logout", null, token, null);
        assertThat(out.status()).isEqualTo(204);
        assertThat(refresh(token).status()).isEqualTo(401);

        // Signed out everywhere by an admin right after a rotation: the rotated token is dead too.
        String first = login("accountant", "accountant1234");
        String second = refresh(first).cookie();
        String admin = send("POST", "/auth/login", Map.of("username", "admin", "password", "admin1234"), null, null).body()
                .get("accessToken").asString();
        long id = jdbc.sql("SELECT id FROM users WHERE username = 'accountant'").query(Long.class).single();
        assertThat(send("POST", "/users/" + id + "/sign-out", null, null, admin).status()).isEqualTo(200);
        assertThat(refresh(first).status()).isEqualTo(401);
        assertThat(refresh(second).status()).isEqualTo(401);
    }

    private long reuseAlerts(String username) {
        return jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'SESSION_REUSE_DETECTED' AND username = ?")
                .param(username).query(Long.class).single();
    }

    private String login(String user, String password) {
        Res r = send("POST", "/auth/login", Map.of("username", user, "password", password), null, null);
        assertThat(r.status()).isEqualTo(200);
        return r.cookie();
    }

    private Res refresh(String cookie) {
        return send("POST", "/auth/refresh", null, cookie, null);
    }

    private Res send(String method, String path, Object body, String refreshCookie, String bearer) {
        var spec = http.method(HttpMethod.valueOf(method)).uri(path);
        if (refreshCookie != null) {
            spec = spec.header(HttpHeaders.COOKIE, "pos_refresh=" + refreshCookie);
        }
        if (bearer != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(body));
        }
        return spec.exchange((req, res) -> {
            String cookie = null;
            List<String> setCookies = res.getHeaders().get(HttpHeaders.SET_COOKIE);
            if (setCookies != null) {
                for (String c : setCookies) {
                    if (c.startsWith("pos_refresh=")) {
                        String v = c.substring("pos_refresh=".length(), c.indexOf(';'));
                        cookie = v.isEmpty() ? null : v;
                    }
                }
            }
            byte[] bytes = res.getBody().readAllBytes();
            return new Res(res.getStatusCode().value(), cookie, bytes.length == 0 ? null : JSON.readTree(bytes));
        });
    }
}
