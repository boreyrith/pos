package com.pos.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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

/**
 * Personal details on user accounts (Khmer and Latin names, gender, date of birth, phone, email):
 * user admins set them; people edit their own in My account, but never their display name, role or
 * branch. Changes are audited.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserDetailsIT {

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

    record Res(int status, JsonNode body) {
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api").build();
        admin = login("admin", "admin1234");
    }

    @Test
    void adminsSetTheDetailsAndPeopleEditTheirOwn() {
        JsonNode me = send("GET", "/auth/me", null, admin).body();
        String username = "details" + (System.nanoTime() % 100_000);
        var create = new HashMap<String, Object>(Map.of("username", username, "password", "secret1234", "fullName", "Sokha",
                "roleId", roleId("CASHIER"), "branchId", me.get("branchId").asLong(), "mustChangePassword", false));
        create.putAll(Map.of("nameKm", "សុខា ចាន់", "nameLatin", "CHAN SOKHA", "gender", "FEMALE", "dateOfBirth", "1995-04-13"));
        Res created = send("POST", "/users", create, admin);
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        JsonNode u = created.body();
        assertThat(u.get("nameKm").asString()).isEqualTo("សុខា ចាន់");
        assertThat(u.get("nameLatin").asString()).isEqualTo("CHAN SOKHA");
        assertThat(u.get("gender").asString()).isEqualTo("FEMALE");
        assertThat(u.get("dateOfBirth").asString()).isEqualTo("1995-04-13");

        // The person edits their own details; a display name, role or branch in the request is ignored.
        String token = login(username, "secret1234");
        var mine = new HashMap<String, Object>(Map.of("nameKm", "សុខា", "nameLatin", "SOKHA CHAN", "gender", "FEMALE",
                "dateOfBirth", "1995-04-14", "phone", "012 345 678", "email", "sokha@example.com"));
        mine.putAll(Map.of("fullName", "Boss", "roleId", roleId("ADMIN"), "username", "boss"));
        Res updated = send("PUT", "/auth/me/details", mine, token);
        assertThat(updated.status()).as(String.valueOf(updated.body())).isEqualTo(200);
        assertThat(updated.body().get("nameLatin").asString()).isEqualTo("SOKHA CHAN");
        assertThat(updated.body().get("phone").asString()).isEqualTo("012 345 678");
        assertThat(updated.body().get("fullName").asString()).isEqualTo("Sokha");
        assertThat(updated.body().get("username").asString()).isEqualTo(username);
        assertThat(updated.body().get("role").get("code").asString()).isEqualTo("CASHIER");
        assertThat(send("GET", "/users/" + u.get("id").asLong(), null, admin).body().get("dateOfBirth").asString()).isEqualTo("1995-04-14");

        // Everything is optional: clearing works too.
        Res cleared = send("PUT", "/auth/me/details", Map.of(), token);
        assertThat(cleared.body().get("nameKm").isNull()).isTrue();
        assertThat(cleared.body().get("gender").isNull()).isTrue();

        // Audited, with what changed.
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'USER_UPDATED' AND entity_id = ? AND summary LIKE '%their own details%'")
                .param(String.valueOf(u.get("id").asLong())).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void detailsAreChecked() {
        String tomorrow = LocalDate.now().plusDays(2).toString();
        Res future = send("PUT", "/auth/me/details", Map.of("dateOfBirth", tomorrow), admin);
        assertThat(future.status()).isEqualTo(400);
        assertThat(future.body().get("detail").asString()).isEqualTo("Enter a date of birth between 1900 and yesterday");
        assertThat(send("PUT", "/auth/me/details", Map.of("dateOfBirth", "1850-01-01"), admin).status()).isEqualTo(400);
        assertThat(send("PUT", "/auth/me/details", Map.of("gender", "UNKNOWN"), admin).status()).isEqualTo(400);
        assertThat(send("PUT", "/auth/me/details", Map.of("email", "not an email"), admin).status()).isEqualTo(400);
        assertThat(send("PUT", "/auth/me/details", Map.of("nameKm", "ក".repeat(101)), admin).status()).isEqualTo(400);
        assertThat(send("PUT", "/auth/me/details", Map.of(), null).status()).isEqualTo(401);
    }

    private long roleId(String code) {
        return jdbc.sql("SELECT id FROM roles WHERE code = ?").param(code).query(Long.class).single();
    }

    private String login(String user, String password) {
        Res r = send("POST", "/auth/login", Map.of("username", user, "password", password), null);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
        return r.body().get("accessToken").asString();
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
