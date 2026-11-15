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
 * Sessions per platform: administration (CTL), the till (CAS) and the mobile app (APP) each sign in
 * with their own cookie; one platform's token never works on another, and signing out of one leaves
 * the others. The device list says which platform each session is on.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionPlatformsIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BROWSER = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final String APP = "Dart/3.9 (dart:io)";

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    RestClient http;

    /** status, the cookies set (name to value; an empty value clears it), the body. */
    record Res(int status, Map<String, String> cookies, JsonNode body) {
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api").build();
    }

    @Test
    void eachPlatformHasItsOwnSessionsAndCookie() {
        Res ctl = login("CTL", BROWSER);
        Res cas = login("CAS", BROWSER);
        Res app = login(null, APP);
        assertThat(ctl.cookies()).containsOnlyKeys("pos_refresh_ctl");
        assertThat(cas.cookies()).containsOnlyKeys("pos_refresh_cas");
        // The mobile app names no platform and keeps the original cookie, so installed apps keep working.
        assertThat(app.cookies()).containsOnlyKeys("pos_refresh");
        String ctlToken = ctl.cookies().get("pos_refresh_ctl");
        String casToken = cas.cookies().get("pos_refresh_cas");
        String appToken = app.cookies().get("pos_refresh");

        // Only these three, then the list labels each one.
        String access = ctl.body().get("accessToken").asString();
        JsonNode list = send("GET", "/auth/sessions", null, null, access, BROWSER).body();
        assertThat(list).hasSize(3);
        Map<String, Integer> byPlatform = new HashMap<>();
        list.forEach(s -> byPlatform.merge(s.get("platform").asString(), 1, Integer::sum));
        assertThat(byPlatform).containsExactlyInAnyOrderEntriesOf(Map.of("CTL", 1, "CAS", 1, "APP", 1));

        // Administration's token sent to the till (in the till's cookie) doesn't sign in there...
        assertThat(send("POST", "/auth/refresh?platform=CAS", null, Map.of("pos_refresh_cas", ctlToken), null, BROWSER).status())
                .isEqualTo(401);
        // ... and is not taken for a stolen token: administration stays signed in.
        Res renewed = send("POST", "/auth/refresh?platform=CTL", null, Map.of("pos_refresh_ctl", ctlToken), null, BROWSER);
        assertThat(renewed.status()).isEqualTo(200);
        ctlToken = renewed.cookies().get("pos_refresh_ctl");
        // With no cookie of its own, a platform is simply not signed in.
        assertThat(send("POST", "/auth/refresh?platform=CAS", null, Map.of("pos_refresh_ctl", ctlToken), null, BROWSER).status())
                .isEqualTo(401);

        // Signing out of the till clears only its cookie and ends only its session.
        Res out = send("POST", "/auth/logout?platform=CAS", null, Map.of("pos_refresh_cas", casToken), null, BROWSER);
        assertThat(out.status()).isEqualTo(204);
        assertThat(out.cookies()).containsOnlyKeys("pos_refresh_cas").containsEntry("pos_refresh_cas", "");
        assertThat(send("POST", "/auth/refresh?platform=CAS", null, Map.of("pos_refresh_cas", casToken), null, BROWSER).status())
                .isEqualTo(401);
        assertThat(send("POST", "/auth/refresh?platform=CTL", null, Map.of("pos_refresh_ctl", ctlToken), null, BROWSER).status())
                .isEqualTo(200);
        assertThat(send("POST", "/auth/refresh", null, Map.of("pos_refresh", appToken), null, APP).status()).isEqualTo(200);

        // The audit log says where each sign-in was.
        assertThat(jdbc.sql("SELECT summary FROM audit_logs WHERE action = 'LOGIN' ORDER BY id DESC LIMIT 3").query(String.class).list())
                .anyMatch(s -> s.startsWith("Signed in to the till on"))
                .anyMatch(s -> s.startsWith("Signed in to administration on"))
                .anyMatch(s -> s.startsWith("Signed in to the mobile app on"));
    }

    @Test
    void anUnknownPlatformIsRefused() {
        var body = new HashMap<String, Object>(Map.of("username", "stock", "password", "stock12345", "platform", "WEB"));
        assertThat(send("POST", "/auth/login", body, null, null, BROWSER).status()).isEqualTo(400);
    }

    /** Signs "tech" in on a platform (null: none named), after ending whatever earlier tests left. */
    private Res login(String platform, String agent) {
        var body = new HashMap<String, Object>(Map.of("username", "tech", "password", "tech12345"));
        if (platform != null) {
            body.put("platform", platform);
        }
        Res r = send("POST", "/auth/login", body, null, null, agent);
        assertThat(r.status()).isEqualTo(200);
        if ("CTL".equals(platform)) {
            // A clean slate: only the three sessions signed in by this test count.
            send("POST", "/auth/sessions/end-others", null, null, r.body().get("accessToken").asString(), agent);
        }
        return r;
    }

    private Res send(String method, String path, Object body, Map<String, String> cookies, String bearer, String agent) {
        var spec = http.method(HttpMethod.valueOf(method)).uri(path).header(HttpHeaders.USER_AGENT, agent);
        if (cookies != null) {
            spec = spec.header(HttpHeaders.COOKIE, cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + "; " + b).orElse(""));
        }
        if (bearer != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(body));
        }
        return spec.exchange((req, res) -> {
            Map<String, String> set = new HashMap<>();
            for (String c : res.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)) {
                int eq = c.indexOf('=');
                int end = c.indexOf(';');
                set.put(c.substring(0, eq), c.substring(eq + 1, end < 0 ? c.length() : end));
            }
            byte[] bytes = res.getBody().readAllBytes();
            return new Res(res.getStatusCode().value(), set, bytes.length == 0 ? null : JSON.readTree(bytes));
        });
    }
}
