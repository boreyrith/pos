package com.pos.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.json.JsonMapper;

/** Prometheus metrics: only the monitoring server's token reads them, not anonymous callers or staff sign-ins. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.metrics-token=test-metrics-token")
class MetricsIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    RestClient http;

    /** status and body as text. */
    record Res(int status, String body) {
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api").build();
    }

    @Test
    void theTokenReadsTheMetrics() {
        Res r = metrics("Bearer test-metrics-token");
        assertThat(r.status()).isEqualTo(200);
        // Requests, the database pool and the JVM, tagged with the application.
        assertThat(r.body()).contains("http_server_requests_seconds", "hikaricp_connections_active", "jvm_memory_used_bytes",
                "application=\"pos-api\"");
    }

    @Test
    void nobodyElseReadsThem() {
        assertThat(metrics(null).status()).isEqualTo(401);
        assertThat(metrics("Bearer wrong-token").status()).isEqualTo(401);
        assertThat(metrics("Bearer test-metrics-token-and-more").status()).isEqualTo(401);
        // An admin's sign-in is for the app, not for the metrics.
        String admin = JSON.readTree(http.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "admin", "password", "admin1234")).retrieve().body(String.class)).get("accessToken").asString();
        assertThat(metrics("Bearer " + admin).status()).isEqualTo(401);
        // Health stays open for the container's health check.
        int health = http.get().uri("/actuator/health").exchange((req, res) -> res.getStatusCode().value());
        assertThat(health).isEqualTo(200);
    }

    private Res metrics(String authorization) {
        return http.get().uri("/actuator/prometheus")
                .headers(h -> {
                    if (authorization != null) {
                        h.set(HttpHeaders.AUTHORIZATION, authorization);
                    }
                })
                .exchange((req, res) -> new Res(res.getStatusCode().value(), new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }
}
