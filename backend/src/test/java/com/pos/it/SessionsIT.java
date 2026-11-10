package com.pos.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Signed-in sessions: listing them, ending your own, and the super admin ending anyone's, at once. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionsIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String LAPTOP = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final String PHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";

    @LocalServerPort
    int port;

    RestClient http;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    com.pos.auth.AuthHousekeepingJob housekeeping;

    /** status, the new refresh cookie (null if none), the body. */
    record Res(int status, String cookie, JsonNode body) {
    }

    /** A signed-in device: its access token and refresh cookie. */
    record Device(String access, String cookie) {
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api").build();
    }

    @Test
    void youSeeYourDevicesAndCanSignOneOut() {
        Device laptop = login("tech", "tech12345", LAPTOP);
        Device phone = login("tech", "tech12345", PHONE);
        // End anything left over from other tests, so only these two are counted.
        send("POST", "/auth/sessions/end-others", null, null, laptop.access(), LAPTOP);
        phone = login("tech", "tech12345", PHONE);

        JsonNode list = send("GET", "/auth/sessions", null, null, laptop.access(), LAPTOP).body();
        assertThat(list).hasSize(2);
        JsonNode current = find(list, true);
        JsonNode other = find(list, false);
        assertThat(current.get("device").asString()).isEqualTo("Chrome on macOS");
        assertThat(other.get("device").asString()).isEqualTo("Safari on iPhone");
        assertThat(other.get("kind").asString()).isEqualTo("PHONE");

        // Renewing keeps the same session (and doesn't add one).
        Res renewed = send("POST", "/auth/refresh", null, phone.cookie(), null, PHONE);
        assertThat(renewed.status()).isEqualTo(200);
        phone = new Device(renewed.body().get("accessToken").asString(), renewed.cookie());
        assertThat(send("GET", "/auth/sessions", null, null, laptop.access(), LAPTOP).body()).hasSize(2);

        // Signing the phone out from the laptop stops it at once: its screen and its renewal.
        assertThat(send("DELETE", "/auth/sessions/" + other.get("id").asString(), null, null, laptop.access(), LAPTOP).status())
                .isEqualTo(204);
        assertThat(send("GET", "/auth/me", null, null, phone.access(), PHONE).status()).isEqualTo(401);
        assertThat(send("POST", "/auth/refresh", null, phone.cookie(), null, PHONE).status()).isEqualTo(401);
        assertThat(send("GET", "/auth/me", null, null, laptop.access(), LAPTOP).status()).isEqualTo(200);

        // Signing out ends the session too.
        assertThat(send("POST", "/auth/logout", null, laptop.cookie(), null, LAPTOP).status()).isEqualTo(204);
        assertThat(send("GET", "/auth/me", null, null, laptop.access(), LAPTOP).status()).isEqualTo(401);
    }

    @Test
    void signOutTheOthersKeepsThisOne() {
        Device a = login("stock", "stock12345", LAPTOP);
        Device b = login("stock", "stock12345", PHONE);
        Device c = login("stock", "stock12345", PHONE);
        int ended = send("POST", "/auth/sessions/end-others", null, null, c.access(), PHONE).body().get("ended").asInt();
        assertThat(ended).isGreaterThanOrEqualTo(2);
        assertThat(send("GET", "/auth/me", null, null, a.access(), LAPTOP).status()).isEqualTo(401);
        assertThat(send("GET", "/auth/me", null, null, b.access(), PHONE).status()).isEqualTo(401);
        assertThat(send("GET", "/auth/sessions", null, null, c.access(), PHONE).body()).hasSize(1);
    }

    @Test
    void theSuperAdminSeesAndEndsAnyonesSession() {
        Device admin = login("admin", "admin1234", LAPTOP);
        Device cashier = login("cashier", "cashier1234", PHONE);
        JsonNode all = send("GET", "/sessions", null, null, admin.access(), LAPTOP).body();
        JsonNode theirs = null;
        for (JsonNode s : all) {
            if (s.get("username").asString().equals("cashier")) {
                theirs = s;
            }
        }
        assertThat(theirs).isNotNull();
        assertThat(send("GET", "/users/" + theirs.get("userId").asLong() + "/sessions", null, null, admin.access(), LAPTOP).body())
                .isNotEmpty();

        // Nobody else can see or end other people's sessions: not a cashier, not even a manager.
        Device manager = login("manager", "manager1234", LAPTOP);
        assertThat(send("GET", "/sessions", null, null, cashier.access(), PHONE).status()).isEqualTo(403);
        assertThat(send("GET", "/sessions", null, null, manager.access(), LAPTOP).status()).isEqualTo(403);
        String adminSession = find(send("GET", "/auth/sessions", null, null, admin.access(), LAPTOP).body(), true).get("id").asString();
        assertThat(send("DELETE", "/auth/sessions/" + adminSession, null, null, cashier.access(), PHONE).status()).isEqualTo(404);

        // Ended by the super admin: refused at once.
        for (JsonNode s : send("GET", "/users/" + theirs.get("userId").asLong() + "/sessions", null, null, admin.access(), LAPTOP).body()) {
            assertThat(send("DELETE", "/sessions/" + s.get("id").asString(), null, null, admin.access(), LAPTOP).status()).isEqualTo(204);
        }
        assertThat(send("GET", "/auth/me", null, null, cashier.access(), PHONE).status()).isEqualTo(401);
        assertThat(send("POST", "/auth/refresh", null, cashier.cookie(), null, PHONE).status()).isEqualTo(401);
        // In the audit log.
        JsonNode audit = send("GET", "/audit-logs?category=USERS&size=20", null, null, admin.access(), LAPTOP).body();
        assertThat(audit.toString()).contains("Signed cashier out on Safari on iPhone");
    }

    @Test
    void tooManyFailedSignInsFromOnePlaceAreSlowedDown() {
        String place = "203.0.113.77";
        for (int i = 0; i < 20; i++) {
            Res r = sendFrom(place, Map.of("username", "nobody-" + i, "password", "guess"));
            assertThat(r.status()).isEqualTo(401);
        }
        Res blocked = sendFrom(place, Map.of("username", "admin", "password", "admin1234"));
        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.body().get("code").asString()).isEqualTo("TOO_MANY_ATTEMPTS");
        // Somewhere else is unaffected.
        assertThat(sendFrom("198.51.100.20", Map.of("username", "admin", "password", "admin1234")).status()).isEqualTo(200);
    }

    @Test
    void oldSignInRecordsAreCleanedUp() {
        Device d = login("tech", "tech12345", LAPTOP);
        String live = find(send("GET", "/auth/sessions", null, null, d.access(), LAPTOP).body(), true).get("id").asString();
        // An old, ended session with an expired token, as months of use would leave behind.
        jdbc.sql("INSERT INTO user_sessions (id, user_id, created_at, last_used_at, expires_at, ended_at, end_reason) "
                + "SELECT 'old-session', id, NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 170 DAY, "
                + "NOW() - INTERVAL 180 DAY, 'SIGNED_OUT' FROM users WHERE username = 'tech'").update();
        jdbc.sql("INSERT INTO refresh_tokens (user_id, session_id, token_hash, expires_at, revoked) "
                + "SELECT id, 'old-session', 'old-token-hash', NOW() - INTERVAL 170 DAY, TRUE FROM users WHERE username = 'tech'").update();

        int[] removed = housekeeping.purge(java.time.Instant.now());
        assertThat(removed[0]).isGreaterThanOrEqualTo(1);
        assertThat(removed[1]).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM user_sessions WHERE id = 'old-session'").query(Long.class).single()).isZero();
        // What is in use stays.
        assertThat(jdbc.sql("SELECT COUNT(*) FROM user_sessions WHERE id = :id").param("id", live).query(Long.class).single()).isOne();
        assertThat(send("POST", "/auth/refresh", null, d.cookie(), null, LAPTOP).status()).isEqualTo(200);
    }

    private Res sendFrom(String ip, Object body) {
        return http.method(HttpMethod.POST).uri("/auth/login").header("X-Real-IP", ip).contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(body))
                .exchange((req, res) -> {
                    byte[] bytes = res.getBody().readAllBytes();
                    return new Res(res.getStatusCode().value(), null, bytes.length == 0 ? null : JSON.readTree(bytes));
                });
    }

    // ---------------------------------------------------------------- helpers

    private Device login(String user, String password, String agent) {
        Res r = send("POST", "/auth/login", Map.of("username", user, "password", password), null, null, agent);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
        return new Device(r.body().get("accessToken").asString(), r.cookie());
    }

    private static JsonNode find(JsonNode list, boolean current) {
        for (JsonNode s : list) {
            if (s.get("current").asBoolean() == current) {
                return s;
            }
        }
        throw new AssertionError("No " + (current ? "current" : "other") + " session in " + list);
    }

    private Res send(String method, String path, Object body, String refreshCookie, String bearer, String agent) {
        var spec = http.method(HttpMethod.valueOf(method)).uri(path).header(HttpHeaders.USER_AGENT, agent);
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
