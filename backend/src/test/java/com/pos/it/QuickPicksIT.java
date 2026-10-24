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

/** Sell-screen quick picks: each person's favourites and the branch's top sellers. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QuickPicksIT {

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
    String manager;

    record Res(int status, JsonNode body) {
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api").build();
        admin = login("admin", "admin1234");
        cashier = login("cashier", "cashier1234");
        manager = login("manager", "manager1234");
    }

    @Test
    void favouritesArePerPersonAndInTheOrderAdded() {
        JsonNode items = get("/offerings?type=PRODUCT&forSale=true&size=50", cashier).body().get("items");
        long a = items.get(4).get("id").asLong();
        long b = items.get(2).get("id").asLong();
        assertThat(send("PUT", "/offerings/" + a + "/favourite", null, cashier).status()).isEqualTo(204);
        assertThat(send("PUT", "/offerings/" + b + "/favourite", null, cashier).status()).isEqualTo(204);
        assertThat(send("PUT", "/offerings/" + a + "/favourite", null, cashier).status()).isEqualTo(204); // again: no change
        assertThat(ids(get("/offerings/favourites", cashier).body())).containsExactly(a, b);
        assertThat(get("/offerings/favourites/ids", cashier).body().size()).isEqualTo(2);
        // Someone else's favourites are their own.
        assertThat(get("/offerings/favourites", manager).body()).isEmpty();
        // Tiles carry stock like the normal list.
        assertThat(get("/offerings/favourites", cashier).body().get(0).get("stock").isNull()).isFalse();

        assertThat(send("DELETE", "/offerings/" + a + "/favourite", null, cashier).status()).isEqualTo(204);
        assertThat(ids(get("/offerings/favourites", cashier).body())).containsExactly(b);
        assertThat(send("PUT", "/offerings/999999/favourite", null, cashier).status()).isEqualTo(404);
        // Only sellers have quick picks.
        assertThat(get("/offerings/favourites", login("stock", "stock12345")).status()).isEqualTo(403);
    }

    @Test
    void topSellersRankByUnitsKept() {
        JsonNode items = get("/offerings?type=PRODUCT&forSale=true&size=50", cashier).body().get("items");
        JsonNode p = items.get(7);
        long id = p.get("id").asLong();
        long before = soldOf(id);
        Res sale = send("POST", "/orders", Map.of("items", List.of(Map.of("offeringId", id, "quantity", 3)), "paymentMethod", "CASH"), cashier);
        assertThat(sale.status()).as(String.valueOf(sale.body())).isEqualTo(201);
        assertThat(soldOf(id)).isEqualTo(before + 3);

        JsonNode top = get("/offerings/top-sellers?days=30&limit=60", cashier).body();
        assertThat(top.size()).isPositive();
        // Ranked from most sold down.
        for (int i = 1; i < top.size(); i++) {
            assertThat(top.get(i).get("sold").asLong()).isLessThanOrEqualTo(top.get(i - 1).get("sold").asLong());
        }
        assertThat(top.get(0).get("offering").get("name").isNull()).isFalse();
    }

    private long soldOf(long id) {
        for (JsonNode t : get("/offerings/top-sellers?limit=60", cashier).body()) {
            if (t.get("offering").get("id").asLong() == id) {
                return t.get("sold").asLong();
            }
        }
        return 0;
    }

    private static List<Long> ids(JsonNode array) {
        return array.valueStream().map(n -> n.get("id").asLong()).toList();
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
