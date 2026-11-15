package com.pos.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * The online shop through the API: settings and who may change them, the public catalog (only what is
 * sold online, never costs), guest and account orders paid by KHQR, the private link, staff moving
 * orders on, delivery and its fee, and what customers must not be able to do.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShopIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern TOTAL_NOW = Pattern.compile("the total is now ([0-9.]+)");

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    RestClient http;
    String admin;
    String manager;
    String cashier;
    String stock;
    Long branchId;
    Long product;
    Long other;
    Long notOnline;
    /** This test's client address: the shop limits requests per address (ShopThrottle). */
    String ip;

    /** status, cookies set (name to value), body. */
    record Res(int status, Map<String, String> cookies, JsonNode body) {
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api").build();
        ip = "10.20." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
        admin = login("admin", "admin1234");
        manager = login("manager", "manager1234");
        cashier = login("cashier", "cashier1234");
        stock = login("stock", "stock12345");
        branchId = jdbc.sql("SELECT branch_id FROM users WHERE username = 'cashier'").query(Long.class).single();
        // Three products with stock in the cashier's selling storage: two sold online, one not.
        List<Long> ids = jdbc.sql("""
                SELECT o.id FROM offerings o JOIN stock_levels l ON l.offering_id = o.id
                JOIN storages s ON s.id = l.storage_id AND s.selling = TRUE AND s.branch_id = ?
                WHERE o.type = 'PRODUCT' AND o.active = TRUE
                GROUP BY o.id HAVING SUM(l.quantity) >= 20 ORDER BY o.id LIMIT 3
                """).param(branchId).query(Long.class).list();
        product = ids.get(0);
        other = ids.get(1);
        notOnline = ids.get(2);
        jdbc.sql("UPDATE offerings SET sell_online = (id IN (?, ?))").param(product).param(other).update();
        // Open, pickup at any branch, delivery from the cashier's branch at 1.50, free from 1000.
        Res saved = send("PUT", "/settings/shop", settings(true, new BigDecimal("1000")), admin, null);
        assertThat(saved.status()).isEqualTo(200);
    }

    @Test
    void onlyThoseWhoChangeCompanySettingsChangeTheShop() {
        assertThat(send("PUT", "/settings/shop", settings(true, null), manager, null).status()).isEqualTo(403);
        assertThat(send("GET", "/settings/shop", null, manager, null).status()).isEqualTo(200);
        var noBranch = settings(true, null);
        noBranch.put("deliveryBranchId", null);
        assertThat(send("PUT", "/settings/shop", noBranch, admin, null).status()).isEqualTo(400);
        // The Delivery service holds the fee.
        Long delivery = send("GET", "/settings/shop", null, admin, null).body().get("deliveryOfferingId").asLong();
        assertThat(jdbc.sql("SELECT price FROM offerings WHERE id = ?").param(delivery).query(BigDecimal.class).single())
                .isEqualByComparingTo("1.50");
    }

    @Test
    void theCatalogShowsOnlyWhatIsSoldOnlineAndNeverCosts() {
        JsonNode store = send("GET", "/shop/store", null, null, null).body();
        assertThat(store.get("open").asBoolean()).isTrue();
        assertThat(store.get("delivery").get("fee").decimalValue()).isEqualByComparingTo("1.50");
        assertThat(store.get("pickupBranches").toString()).contains("\"id\":" + branchId);

        JsonNode page = send("GET", "/shop/products?branchId=" + branchId, null, null, null).body();
        assertThat(page.get("totalItems").asLong()).isEqualTo(2);
        assertThat(page.toString()).doesNotContain("\"cost\"").doesNotContain("\"sku\"");
        assertThat(send("GET", "/shop/products/" + notOnline + "?branchId=" + branchId, null, null, null).status()).isEqualTo(404);
    }

    @Test
    void aGuestOrdersPaysAndStaffHandTheOrderOver() {
        int before = stockOf(product);
        var order = order("PICKUP", 2, null);
        // The customer must be shown the right total.
        order.put("expectedTotal", new BigDecimal("0.01"));
        Res wrong = send("POST", "/shop/orders", order, null, null);
        assertThat(wrong.status()).isEqualTo(409);
        assertThat(wrong.body().get("code").asString()).isEqualTo("PRICES_CHANGED");

        Res placed = place(order, null);
        String number = placed.body().get("order").get("orderNumber").asString();
        String key = placed.body().get("accessKey").asString();
        assertThat(placed.body().get("order").get("orderStatus").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(placed.body().get("order").get("payment").get("khqrPayload").asString()).isNotBlank();
        // The stock is held while it waits for payment.
        assertThat(stockOf(product)).isEqualTo(before - 2);

        // Only with its link.
        assertThat(send("GET", "/shop/orders/" + number, null, null, null).status()).isEqualTo(404);
        assertThat(send("GET", "/shop/orders/" + number + "?key=wrong", null, null, null).status()).isEqualTo(404);
        // Staff don't prepare it before it is paid.
        Long orderId = jdbc.sql("SELECT id FROM orders WHERE order_number = ?").param(number).query(Long.class).single();
        assertThat(send("POST", "/online-orders/" + orderId + "/status", Map.of("status", "READY"), cashier, null).status())
                .isEqualTo(409);

        JsonNode paid = send("POST", "/shop/orders/" + number + "/payment/simulate?key=" + key, null, null, null).body();
        assertThat(paid.get("orderStatus").asString()).isEqualTo("COMPLETED");
        assertThat(paid.get("status").asString()).isEqualTo("NEW");
        // An ordinary sale by the Online shop user.
        assertThat(jdbc.sql("SELECT u.username FROM orders o JOIN users u ON u.id = o.cashier_id WHERE o.id = ?")
                .param(orderId).query(String.class).single()).isEqualTo("online-shop");

        assertThat(send("GET", "/online-orders/count", null, cashier, null).body().get("new").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(send("GET", "/online-orders?view=NEW", null, cashier, null).body().toString()).contains(number);
        assertThat(send("GET", "/online-orders?view=NEW", null, stock, null).status()).isEqualTo(403);
        // Pickup orders get ready, not out for delivery.
        assertThat(send("POST", "/online-orders/" + orderId + "/status", Map.of("status", "OUT_FOR_DELIVERY"), cashier, null).status())
                .isEqualTo(409);
        assertThat(send("POST", "/online-orders/" + orderId + "/status", Map.of("status", "READY"), cashier, null).status())
                .isEqualTo(200);
        assertThat(send("POST", "/online-orders/" + orderId + "/status", Map.of("status", "DONE"), cashier, null).status())
                .isEqualTo(200);
        assertThat(send("GET", "/shop/orders/" + number + "?key=" + key, null, null, null).body().get("status").asString())
                .isEqualTo("DONE");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'ONLINE_ORDER_UPDATED' AND entity_id = ?")
                .param(String.valueOf(orderId)).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void deliveryAddsItsFeeUnlessTheGoodsReachTheFreeAmount() {
        var delivery = order("DELIVERY", 1, null);
        delivery.remove("address");
        assertThat(send("POST", "/shop/orders", delivery, null, null).status()).isEqualTo(400);
        delivery.put("address", "St 271, Phnom Penh");
        JsonNode lines = place(delivery, null).body().get("order").get("items");
        assertThat(lines).anyMatch(l -> l.get("delivery").asBoolean() && l.get("lineTotal").decimalValue().compareTo(new BigDecimal("1.50")) == 0);

        // The promotions preview has the delivery line too, last, as the order will.
        var preview = new HashMap<String, Object>(Map.of("branchId", branchId, "fulfilment", "DELIVERY",
                "items", List.of(Map.of("offeringId", product, "quantity", 1))));
        assertThat(send("POST", "/shop/promotions", preview, null, null).body().get("lines")).hasSize(2);
        preview.put("fulfilment", "PICKUP");
        assertThat(send("POST", "/shop/promotions", preview, null, null).body().get("lines")).hasSize(1);

        send("PUT", "/settings/shop", settings(true, new BigDecimal("1")), admin, null);
        JsonNode free = place(order("DELIVERY", 1, "St 271, Phnom Penh"), null).body().get("order").get("items");
        assertThat(free).noneMatch(l -> l.get("delivery").asBoolean());
    }

    @Test
    void customersSeeOnlyTheirOwnOrders() {
        Res signUp = send("POST", "/shop/account/signup", Map.of("name", "Sophea", "phone", "012 555 001", "password", "sophea-pass"), null, null);
        assertThat(signUp.status()).isEqualTo(200);
        String sophea = signUp.cookies().get("pos_shop");
        assertThat(sophea).isNotBlank();
        // The same number again: sign in instead.
        assertThat(send("POST", "/shop/account/signup", Map.of("name", "X", "phone", "012555001", "password", "another-pass"), null, null)
                .status()).isEqualTo(409);

        Res placed = place(order("PICKUP", 1, null), sophea);
        String number = placed.body().get("order").get("orderNumber").asString();
        assertThat(placed.body().get("accessKey").isNull()).isTrue();
        assertThat(send("GET", "/shop/account/orders", null, null, sophea).body().toString()).contains(number);
        assertThat(send("GET", "/shop/orders/" + number, null, null, sophea).status()).isEqualTo(200);

        String dara = send("POST", "/shop/account/signup", Map.of("name", "Dara", "phone", "012555002", "password", "dara-pass-1"), null, null)
                .cookies().get("pos_shop");
        assertThat(send("GET", "/shop/orders/" + number, null, null, dara).status()).isEqualTo(404);
        assertThat(send("GET", "/shop/account/orders", null, null, dara).body()).isEmpty();
        // A staff sign-in is not a shop sign-in.
        assertThat(send("GET", "/shop/account", null, admin, null).status()).isEqualTo(401);

        // Signing out ends the session.
        assertThat(send("POST", "/shop/account/signout", null, null, dara).status()).isEqualTo(204);
        assertThat(send("GET", "/shop/account", null, null, dara).status()).isEqualTo(401);
    }

    @Test
    void wrongPasswordsLockTheAccount() {
        send("POST", "/shop/account/signup", Map.of("name", "Lock", "phone", "012555099", "password", "right-pass"), null, null);
        for (int i = 0; i < 4; i++) {
            assertThat(signIn("012555099", "wrong-pass", "10.9.0." + i).status()).isEqualTo(401);
        }
        assertThat(signIn("012555099", "wrong-pass", "10.9.0.9").status()).isEqualTo(403);
        assertThat(signIn("012555099", "right-pass", "10.9.0.10").status()).isEqualTo(403);
    }

    @Test
    void whatIsntSoldOnlineOrAClosedShopIsRefused() {
        var bad = order("PICKUP", 1, null);
        bad.put("items", List.of(Map.of("offeringId", notOnline, "quantity", 1)));
        assertThat(send("POST", "/shop/orders", bad, null, null).status()).isEqualTo(400);
        var tooMany = order("PICKUP", 51, null);
        assertThat(send("POST", "/shop/orders", tooMany, null, null).status()).isEqualTo(400);

        send("PUT", "/settings/shop", settings(false, null), admin, null);
        assertThat(send("GET", "/shop/store", null, null, null).body().get("open").asBoolean()).isFalse();
        assertThat(send("POST", "/shop/orders", order("PICKUP", 1, null), null, null).status()).isEqualTo(409);
    }

    @Test
    void aNumberAlreadyOnFileIsConfirmedByStaffBeforeItGetsTheCustomersBenefits() {
        // A member added at the till.
        String phone = "0129" + (1000000 + (int) (Math.random() * 8999999));
        Long member = send("POST", "/customers", Map.of("name", "Member at the till", "phone", phone), manager, null).body().get("id").asLong();
        Res signUp = send("POST", "/shop/account/signup", Map.of("name", "Someone", "phone", phone, "password", "claim-pass-1"), null, null);
        assertThat(signUp.status()).isEqualTo(200);
        assertThat(signUp.body().get("verified").asBoolean()).isFalse();
        String cookie = signUp.cookies().get("pos_shop");

        // Unconfirmed: the order is not on the member's record (no points, no member offers).
        String number = place(order("PICKUP", 1, null), cookie).body().get("order").get("orderNumber").asString();
        assertThat(jdbc.sql("SELECT c.is_default FROM orders o JOIN customers c ON c.id = o.customer_id WHERE o.order_number = ?")
                .param(number).query(Boolean.class).single()).isTrue();

        // Staff see it on the customer and confirm it; only those who manage customers.
        assertThat(send("GET", "/customers/" + member + "/online-account", null, cashier, null).body().get("verified").asBoolean()).isFalse();
        assertThat(send("POST", "/customers/" + member + "/online-account/verify", null, stock, null).status()).isEqualTo(403);
        assertThat(send("POST", "/customers/" + member + "/online-account/verify", null, manager, null).status()).isEqualTo(200);
        String next = place(order("PICKUP", 1, null), cookie).body().get("order").get("orderNumber").asString();
        assertThat(jdbc.sql("SELECT customer_id FROM orders WHERE order_number = ?").param(next).query(Long.class).single()).isEqualTo(member);

        // Removed (e.g. someone else had registered the number): signed out, its orders kept as guest orders.
        assertThat(send("DELETE", "/customers/" + member + "/online-account", null, manager, null).status()).isEqualTo(204);
        assertThat(send("GET", "/shop/account", null, null, cookie).status()).isEqualTo(401);
        assertThat(send("GET", "/customers/" + member + "/online-account", null, manager, null).status()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM online_orders oo JOIN orders o ON o.id = oo.order_id WHERE o.order_number IN (?, ?)"
                + " AND oo.account_id IS NULL").params(number, next).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void aCustomerCancelsAnUnpaidOrderAndTheStockComesBack() {
        int before = stockOf(product);
        Res placed = place(order("PICKUP", 3, null), null);
        String number = placed.body().get("order").get("orderNumber").asString();
        String key = placed.body().get("accessKey").asString();
        assertThat(stockOf(product)).isEqualTo(before - 3);
        assertThat(send("POST", "/shop/orders/" + number + "/cancel?key=wrong", null, null, null).status()).isEqualTo(404);
        JsonNode cancelled = send("POST", "/shop/orders/" + number + "/cancel?key=" + key, null, null, null).body();
        assertThat(cancelled.get("orderStatus").asString()).isEqualTo("CANCELLED");
        assertThat(stockOf(product)).isEqualTo(before);
        // Paid orders are refunded by the shop, not cancelled.
        Res paid = place(order("PICKUP", 1, null), null);
        String paidNumber = paid.body().get("order").get("orderNumber").asString();
        String paidKey = paid.body().get("accessKey").asString();
        send("POST", "/shop/orders/" + paidNumber + "/payment/simulate?key=" + paidKey, null, null, null);
        assertThat(send("POST", "/shop/orders/" + paidNumber + "/cancel?key=" + paidKey, null, null, null).status()).isEqualTo(409);
    }

    @Test
    void staffOfAnotherBranchCantSeeOrPayThisBranchsPayments() {
        String number = place(order("PICKUP", 1, null), null).body().get("order").get("orderNumber").asString();
        Long paymentId = jdbc.sql("SELECT p.id FROM payments p JOIN orders o ON o.id = p.order_id WHERE o.order_number = ?")
                .param(number).query(Long.class).single();
        String otherBranch = login("srmanager", "srmanager1234");
        assertThat(send("GET", "/payments/" + paymentId, null, otherBranch, null).status()).isEqualTo(403);
        assertThat(send("POST", "/payments/" + paymentId + "/simulate-paid", null, otherBranch, null).status()).isEqualTo(403);
        assertThat(send("GET", "/payments/" + paymentId, null, cashier, null).status()).isEqualTo(200);
    }

    @Test
    void theDeliveryServiceIsNotOnTheTill() {
        Long delivery = send("GET", "/settings/shop", null, admin, null).body().get("deliveryOfferingId").asLong();
        JsonNode sellScreen = send("GET", "/offerings?forSale=true&type=SERVICE&size=200", null, cashier, null).body();
        assertThat(sellScreen.toString()).doesNotContain("\"id\":" + delivery + ",");
    }

    @Test
    void signUpsAreLimitedPerAddress() {
        for (int i = 0; i < 5; i++) {
            assertThat(send("POST", "/shop/account/signup", Map.of("name", "N" + i, "phone", "01277" + (100000 + i), "password", "limit-pass-" + i),
                    null, null).status()).isEqualTo(200);
        }
        Res sixth = send("POST", "/shop/account/signup", Map.of("name", "N6", "phone", "012777000", "password", "limit-pass-6"), null, null);
        assertThat(sixth.status()).isEqualTo(429);
        assertThat(sixth.body().get("code").asString()).isEqualTo("TOO_MANY_ATTEMPTS");
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> settings(boolean open, BigDecimal freeFrom) {
        var m = new HashMap<String, Object>();
        m.put("enabled", open);
        m.put("pickupEnabled", true);
        m.put("deliveryEnabled", true);
        m.put("deliveryBranchId", branchId);
        m.put("deliveryFee", new BigDecimal("1.50"));
        m.put("freeDeliveryFrom", freeFrom);
        return m;
    }

    private Map<String, Object> order(String fulfilment, int quantity, String address) {
        var m = new HashMap<String, Object>();
        m.put("branchId", branchId);
        m.put("fulfilment", fulfilment);
        m.put("items", List.of(Map.of("offeringId", product, "quantity", quantity)));
        m.put("contactName", "Guest Buyer");
        m.put("contactPhone", "+85512" + (100000 + (int) (Math.random() * 899999)));
        m.put("address", address);
        return m;
    }

    /** Places an order with the total the server works out (read from its PRICES_CHANGED answer). */
    private Res place(Map<String, Object> order, String shopCookie) {
        order.put("expectedTotal", new BigDecimal("0.01"));
        Res first = send("POST", "/shop/orders", order, null, shopCookie);
        assertThat(first.status()).as(String.valueOf(first.body())).isEqualTo(409);
        Matcher m = TOTAL_NOW.matcher(first.body().get("detail").asString());
        assertThat(m.find()).isTrue();
        order.put("expectedTotal", new BigDecimal(m.group(1)));
        Res placed = send("POST", "/shop/orders", order, null, shopCookie);
        assertThat(placed.status()).as(String.valueOf(placed.body())).isEqualTo(201);
        return placed;
    }

    private int stockOf(Long offeringId) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(l.quantity), 0) FROM stock_levels l JOIN storages s ON s.id = l.storage_id
                WHERE l.offering_id = ? AND s.selling = TRUE AND s.branch_id = ?
                """).param(offeringId).param(branchId).query(Integer.class).single();
    }

    private Res signIn(String phone, String password, String ip) {
        var spec = http.post().uri("/shop/account/signin").header("X-Real-IP", ip).contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(Map.of("phone", phone, "password", password)));
        return spec.exchange((req, res) -> {
            byte[] bytes = res.getBody().readAllBytes();
            return new Res(res.getStatusCode().value(), Map.of(), bytes.length == 0 ? null : JSON.readTree(bytes));
        });
    }

    private String login(String user, String password) {
        return send("POST", "/auth/login", Map.of("username", user, "password", password), null, null).body().get("accessToken").asString();
    }

    private Res send(String method, String path, Object body, String bearer, String shopCookie) {
        var spec = http.method(HttpMethod.valueOf(method)).uri(path).header("X-Real-IP", ip);
        if (bearer != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        if (shopCookie != null) {
            spec = spec.header(HttpHeaders.COOKIE, "pos_shop=" + shopCookie);
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
