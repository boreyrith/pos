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

/** Catalog barcodes: several per entry, unique across the catalog, generated in-store codes, scanning by any. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogBarcodesIT {

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

    record Res(int status, JsonNode body) {
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api").build();
        admin = login("admin", "admin1234");
        cashier = login("cashier", "cashier1234");
    }

    @Test
    void entriesHaveSeveralUniqueBarcodes() {
        List<String> generated = codes(get("/offerings/barcodes/next?count=2", admin).body());
        assertThat(generated).hasSize(2).allSatisfy(c -> assertThat(c).startsWith("200").hasSize(13));
        assertThat(validEan13(generated.get(0))).isTrue();
        assertThat(generated.get(0)).isNotEqualTo(generated.get(1));
        // Only catalog managers generate codes.
        assertThat(get("/offerings/barcodes/next", cashier).status()).isEqualTo(403);

        String maker = "8851234567895";
        Res created = send("POST", "/offerings", product("Barcode test " + System.nanoTime(), List.of(maker, generated.get(0))), admin);
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        JsonNode p = created.body();
        assertThat(p.get("barcode").asString()).isEqualTo(maker);
        assertThat(codes(p.get("barcodes"))).containsExactly(maker, generated.get(0));

        // Scanning and searching find it by either code.
        assertThat(get("/offerings/barcode/" + generated.get(0), cashier).body().get("id").asLong()).isEqualTo(p.get("id").asLong());
        assertThat(get("/offerings?q=" + maker, admin).body().get("items").get(0).get("id").asLong()).isEqualTo(p.get("id").asLong());
        // The next generated code moves past the one in use.
        assertThat(codes(get("/offerings/barcodes/next", admin).body())).doesNotContain(generated.get(0));

        // Another entry can't take either code, nor use a code twice, nor use someone's SKU.
        Res taken = send("POST", "/offerings", product("Other " + System.nanoTime(), List.of(generated.get(0))), admin);
        assertThat(taken.status()).isEqualTo(409);
        assertThat(taken.body().get("code").asString()).isEqualTo("BARCODE_TAKEN");
        assertThat(send("POST", "/offerings", product("Twice " + System.nanoTime(), List.of("111", "111")), admin).status()).isEqualTo(400);
        assertThat(send("POST", "/offerings", product("Sku " + System.nanoTime(), List.of(p.get("sku").asString())), admin).status()).isEqualTo(409);
        assertThat(send("POST", "/offerings", product("Bad " + System.nanoTime(), List.of("has space")), admin).status()).isEqualTo(400);

        // Editing: drop the maker's code, reorder; the freed code can be used elsewhere.
        Map<String, Object> edit = product(p.get("name").asString(), List.of(generated.get(0)));
        Res updated = send("PUT", "/offerings/" + p.get("id").asLong(), edit, admin);
        assertThat(updated.status()).as(String.valueOf(updated.body())).isEqualTo(200);
        assertThat(codes(updated.body().get("barcodes"))).containsExactly(generated.get(0));
        assertThat(updated.body().get("barcode").asString()).isEqualTo(generated.get(0));
        assertThat(send("POST", "/offerings", product("Gets freed code " + System.nanoTime(), List.of(maker)), admin).status()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM offering_barcodes WHERE code = ?").param(maker).query(Long.class).single()).isEqualTo(1);
    }

    private static Map<String, Object> product(String name, List<String> barcodes) {
        var m = new HashMap<String, Object>();
        m.put("type", "PRODUCT");
        m.put("name", name);
        m.put("nameKm", "សាកល្បង");
        m.put("price", 5);
        m.put("barcodes", barcodes);
        return m;
    }

    private static List<String> codes(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }

    private static boolean validEan13(String code) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (code.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return (10 - sum % 10) % 10 == code.charAt(12) - '0';
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
