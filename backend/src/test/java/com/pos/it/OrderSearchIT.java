package com.pos.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

/**
 * Searching sales: any part of the order number or of the name on the order (the full-text index),
 * the customer's phone or code, together with the other filters, a page at a time.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderSearchIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

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
    void findsAnyPartOfTheNameOrTheOrderNumber() {
        // A name with "a" in it: with stopwords on, the index would never find pieces such as "ha".
        String tag = Long.toString(System.nanoTime() % 100_000);
        String name = "Bopha Keo " + tag;
        JsonNode first = sell(null, name);
        JsonNode second = sell(null, name);

        // Part of the name, across the space, in any case: both, newest first.
        JsonNode found = search("q=" + enc("PHA KEO " + tag), cashier);
        assertThat(ids(found)).containsExactly(id(second), id(first));
        assertThat(found.get("totalItems").asLong()).isEqualTo(2);

        // A page at a time.
        JsonNode page2 = search("q=" + enc("keo " + tag) + "&size=1&page=1", cashier);
        assertThat(ids(page2)).containsExactly(id(first));
        assertThat(page2.get("totalItems").asLong()).isEqualTo(2);

        // The whole order number, and its last digits, as printed on the receipt.
        String number = first.get("orderNumber").asString();
        assertThat(ids(search("q=" + enc(number), cashier))).containsExactly(id(first));
        assertThat(ids(search("q=" + enc(number.substring(number.length() - 4)) + "&size=100", cashier))).contains(id(first));

        // Together with the other filters.
        assertThat(ids(search("q=" + enc(name) + "&status=COMPLETED", cashier))).containsExactly(id(second), id(first));
        assertThat(search("q=" + enc(name) + "&status=CANCELLED", cashier).get("totalItems").asLong()).isZero();
        assertThat(search("q=" + enc(name) + "&customerId=" + first.get("customerId").asLong(), cashier).get("totalItems").asLong())
                .isEqualTo(2);
        assertThat(search("q=" + enc(name) + "&from=2020-01-01&to=2020-01-31", cashier).get("totalItems").asLong()).isZero();
        assertThat(search("q=" + enc("nobody by this name " + tag), cashier).get("totalItems").asLong()).isZero();
    }

    @Test
    void findsANamedCustomersOrdersByPhoneOrCode() {
        String phone = "097" + (1_000_000 + System.nanoTime() % 9_000_000);
        Res created = send("POST", "/customers", Map.of("name", "Dara Search", "phone", phone), cashier);
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        JsonNode customer = created.body();
        JsonNode order = sell(customer.get("id").asLong(), null);

        assertThat(ids(search("q=" + phone.substring(2, 9), cashier))).containsExactly(id(order));
        assertThat(ids(search("q=" + enc(customer.get("code").asString()), cashier))).containsExactly(id(order));
    }

    @Test
    void oddTextIsTakenLiterally() {
        // Full-text operators, quotes and LIKE wildcards are text to find, never syntax or "anything".
        for (String q : List.of("\"", "\"\"", "+-*", "a*", "(x)", "%", "%%", "_", "1%", "@<>~", "  x  ")) {
            Res r = send("GET", "/orders?q=" + enc(q), null, cashier);
            assertThat(r.status()).as(q + ": " + r.body()).isEqualTo(200);
        }
        assertThat(search("q=" + enc("%%"), cashier).get("totalItems").asLong()).isZero();
        assertThat(search("q=" + enc("__"), cashier).get("totalItems").asLong()).isZero();
        // One character can't use the index; it is still searched.
        String tag = "Zq" + (System.nanoTime() % 1000);
        JsonNode o = sell(null, tag);
        assertThat(ids(search("q=Z&size=100", cashier))).contains(id(o));
    }

    private JsonNode search(String query, String token) {
        Res r = send("GET", "/orders?" + query, null, token);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
        return r.body();
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("id").asLong()));
        return out;
    }

    private static long id(JsonNode order) {
        return order.get("id").asLong();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private JsonNode sell(Long customerId, String name) {
        JsonNode p = send("GET", "/offerings?type=PRODUCT&size=50", null, admin).body().get("items").get(3);
        var m = new HashMap<String, Object>(Map.of("items", List.of(Map.of("offeringId", p.get("id").asLong(), "quantity", 1)), "paymentMethod", "CASH"));
        if (customerId != null) {
            m.put("customerId", customerId);
        }
        if (name != null) {
            m.put("customerName", name);
        }
        Res r = send("POST", "/orders", m, cashier);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return r.body();
    }

    private String login(String user, String password) {
        return send("POST", "/auth/login", Map.of("username", user, "password", password), null).body().get("accessToken").asString();
    }

    private Res send(String method, String path, Object body, String token) {
        var spec = http.method(HttpMethod.valueOf(method)).uri(java.net.URI.create("http://localhost:" + port + "/api" + path));
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
