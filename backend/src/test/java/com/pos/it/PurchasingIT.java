package com.pos.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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

/** Purchasing end to end: vendors, orders, deliveries into stock, invoices, payments, permissions. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PurchasingIT {

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
    String stock;
    String accountant;
    String cashier;

    record Res(int status, JsonNode body) {
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api").build();
        admin = login("admin", "admin1234");
        manager = login("manager", "manager1234");
        stock = login("stock", "stock12345");
        accountant = login("accountant", "accountant1234");
        cashier = login("cashier", "cashier1234");
    }

    @Test
    void orderReceiveInvoicePay() {
        JsonNode vendor = createVendor("V" + (System.nanoTime() % 100000), 30);
        JsonNode p1 = product(0);
        JsonNode p2 = product(1);
        long storage = sellingStorage();
        int s1 = stockOf(p1);

        // Draft by a manager; cashiers can't see purchasing at all.
        assertThat(get("/purchase-orders", cashier).status()).isEqualTo(403);
        Res draft = send("POST", "/purchase-orders", order(vendor, storage, List.of(line(p1, 10, "1.25"), line(p2, 4, "2.10"))), manager);
        assertThat(draft.status()).as(String.valueOf(draft.body())).isEqualTo(201);
        JsonNode po = draft.body();
        long id = po.get("id").asLong();
        assertThat(po.get("poNumber").asString()).startsWith("PO-");
        assertThat(po.get("status").asString()).isEqualTo("DRAFT");
        assertThat(dec(po, "total")).isEqualByComparingTo("20.90");

        // Drafts can change; nothing can be received until it is placed.
        assertThat(send("PUT", "/purchase-orders/" + id, order(vendor, storage, List.of(line(p1, 12, "1.25"), line(p2, 4, "2.10"))), manager).status())
                .isEqualTo(200);
        long l1 = lineId(id, p1);
        assertThat(receive(id, Map.of(l1, 5), false, stock).status()).isEqualTo(409);
        assertThat(send("POST", "/purchase-orders/" + id + "/place", null, stock).status()).isEqualTo(403);
        assertThat(send("POST", "/purchase-orders/" + id + "/place", null, manager).body().get("status").asString()).isEqualTo("ORDERED");
        assertThat(send("PUT", "/purchase-orders/" + id, order(vendor, storage, List.of(line(p1, 1, "1"))), manager).status()).isEqualTo(409);

        // Inventory staff receive part of it: stock goes up at once, history links to the delivery.
        assertThat(receive(id, Map.of(l1, 5), true, stock).status()).as("stock staff can't change costs").isEqualTo(403);
        Res part = receive(id, Map.of(l1, 5), false, stock);
        assertThat(part.status()).as(String.valueOf(part.body())).isEqualTo(200);
        assertThat(part.body().get("status").asString()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(stockOf(p1)).isEqualTo(s1 + 5);
        String grn = part.body().get("receipts").get(0).get("receiptNumber").asString();
        assertThat(grn).startsWith("GRN-");
        Long moved = jdbc.sql("SELECT COUNT(*) FROM stock_movements WHERE type = 'PURCHASE' AND goods_receipt_id IS NOT NULL AND offering_id = ?")
                .param(p1.get("id").asLong()).query(Long.class).single();
        assertThat(moved).isEqualTo(1);
        // Can't receive more than ordered, and a partly received order can't be cancelled.
        assertThat(receive(id, Map.of(l1, 8), false, stock).status()).isEqualTo(400);
        assertThat(send("POST", "/purchase-orders/" + id + "/cancel", null, manager).status()).isEqualTo(409);

        // The rest arrives; the manager updates catalog costs from it.
        long l2 = lineId(id, p2);
        Res rest = receive(id, Map.of(l1, 7, l2, 4), true, manager);
        assertThat(rest.body().get("status").asString()).isEqualTo("RECEIVED");
        assertThat(stockOf(p1)).isEqualTo(s1 + 12);
        assertThat(get("/offerings/" + p2.get("id").asLong(), admin).body().get("cost").decimalValue()).isEqualByComparingTo("2.10");
        assertThat(receive(id, Map.of(l1, 1), false, manager).status()).isEqualTo(409);

        // The accountant records the vendor's invoice; due date follows the vendor's terms.
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Phnom_Penh"));
        Map<String, Object> inv = new HashMap<>(Map.of("vendorId", vendor.get("id").asLong(), "purchaseOrderId", id, "invoiceNumber", "INV-77",
                "invoiceDate", today.toString(), "subtotal", new BigDecimal("23.40"), "tax", new BigDecimal("2.34")));
        assertThat(send("POST", "/vendor-invoices", inv, manager).status()).isEqualTo(403);
        Res invoice = send("POST", "/vendor-invoices", inv, accountant);
        assertThat(invoice.status()).as(String.valueOf(invoice.body())).isEqualTo(201);
        long invId = invoice.body().get("id").asLong();
        assertThat(dec(invoice.body(), "total")).isEqualByComparingTo("25.74");
        assertThat(invoice.body().get("dueDate").asString()).isEqualTo(today.plusDays(30).toString());
        assertThat(send("POST", "/vendor-invoices", inv, accountant).status()).isEqualTo(409);
        assertThat(balanceOf(vendor)).isEqualByComparingTo("25.74");

        // Payments: partial, too much, the rest.
        Res paid = send("POST", "/vendor-invoices/" + invId + "/payments", payment("10.00", today), accountant);
        assertThat(paid.body().get("status").asString()).isEqualTo("PARTIALLY_PAID");
        assertThat(send("POST", "/vendor-invoices/" + invId + "/payments", payment("20.00", today), accountant).status()).isEqualTo(400);
        assertThat(send("POST", "/vendor-invoices/" + invId + "/payments", payment("1.00", today.plusDays(2)), accountant).status()).isEqualTo(400);
        assertThat(send("POST", "/vendor-invoices/" + invId + "/void", null, accountant).status()).isEqualTo(409);
        Res done = send("POST", "/vendor-invoices/" + invId + "/payments", payment("15.74", today), accountant);
        assertThat(done.body().get("status").asString()).isEqualTo("PAID");
        assertThat(done.body().get("payments")).hasSize(2);
        assertThat(balanceOf(vendor)).isZero();
        assertThat(get("/purchase-orders/" + id, manager).body().get("invoices")).hasSize(1);

        String log = jdbc.sql("SELECT GROUP_CONCAT(action) FROM audit_logs WHERE category = 'PURCHASING'").query(String.class).single();
        assertThat(log).contains("PO_CREATED", "PO_ORDERED", "GOODS_RECEIVED", "VENDOR_INVOICE_RECORDED", "VENDOR_PAYMENT_RECORDED");
    }

    @Test
    void shortDeliveriesCancellationAndBranches() {
        JsonNode vendor = createVendor("S" + (System.nanoTime() % 100000), 0);
        JsonNode p = product(2);
        long storage = sellingStorage();
        long id = send("POST", "/purchase-orders", order(vendor, storage, List.of(line(p, 6, "1.00"))), admin).body().get("id").asLong();
        send("POST", "/purchase-orders/" + id + "/place", null, admin);
        // Another branch's manager can't see or receive it.
        String sr = login("srmanager", "srmanager1234");
        assertThat(get("/purchase-orders/" + id, sr).status()).isEqualTo(404);
        assertThat(receive(id, Map.of(lineId(id, p), 1), false, sr).status()).isEqualTo(404);

        receive(id, Map.of(lineId(id, p), 4), false, admin);
        Res closed = send("POST", "/purchase-orders/" + id + "/close", null, admin);
        assertThat(closed.body().get("status").asString()).isEqualTo("RECEIVED");
        assertThat(closed.body().get("lines").get(0).get("remaining").asInt()).isEqualTo(2);

        long other = send("POST", "/purchase-orders", order(vendor, storage, List.of(line(p, 1, "1.00"))), admin).body().get("id").asLong();
        assertThat(send("POST", "/purchase-orders/" + other + "/cancel", null, admin).body().get("status").asString()).isEqualTo("CANCELLED");
        // Services can't be purchased into stock.
        JsonNode service = get("/offerings?type=SERVICE&size=5", admin).body().get("items").get(0);
        assertThat(send("POST", "/purchase-orders", order(vendor, storage, List.of(line(service, 1, "1.00"))), admin).status()).isEqualTo(400);
    }

    // helpers

    private JsonNode createVendor(String code, int terms) {
        Res r = send("POST", "/vendors", Map.of("code", code, "name", "Vendor " + code, "paymentTermsDays", terms), manager);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return r.body();
    }

    private BigDecimal balanceOf(JsonNode vendor) {
        for (JsonNode v : get("/vendors", accountant).body()) {
            if (v.get("id").asLong() == vendor.get("id").asLong()) {
                return v.get("balance").decimalValue();
            }
        }
        throw new AssertionError("vendor");
    }

    private static Map<String, Object> order(JsonNode vendor, long storage, List<Map<String, Object>> lines) {
        return Map.of("vendorId", vendor.get("id").asLong(), "storageId", storage, "lines", lines);
    }

    private static Map<String, Object> line(JsonNode offering, int qty, String cost) {
        return Map.of("offeringId", offering.get("id").asLong(), "quantity", qty, "unitCost", new BigDecimal(cost));
    }

    private static Map<String, Object> payment(String amount, LocalDate on) {
        return Map.of("amount", new BigDecimal(amount), "method", "BANK_TRANSFER", "paidOn", on.toString(), "reference", "TRX-1");
    }

    private Res receive(long id, Map<Long, Integer> lines, boolean updateCost, String token) {
        return send("POST", "/purchase-orders/" + id + "/receive", Map.of("lines",
                lines.entrySet().stream().map(e -> Map.of("lineId", e.getKey(), "quantity", e.getValue())).toList(),
                "updateCost", updateCost, "deliveryRef", "DN-1"), token);
    }

    private long lineId(long poId, JsonNode offering) {
        for (JsonNode l : get("/purchase-orders/" + poId, admin).body().get("lines")) {
            if (l.get("offeringId").asLong() == offering.get("id").asLong()) {
                return l.get("id").asLong();
            }
        }
        throw new AssertionError("line");
    }

    private JsonNode product(int n) {
        return get("/offerings?type=PRODUCT&size=100", admin).body().get("items").get(n);
    }

    private int stockOf(JsonNode offering) {
        return get("/offerings/" + offering.get("id").asLong(), manager).body().get("stock").asInt();
    }

    private long sellingStorage() {
        for (JsonNode b : get("/branches", admin).body()) {
            if (b.get("code").asString().equals("MAIN")) {
                for (JsonNode s : b.get("storages")) {
                    if (s.get("selling").asBoolean()) {
                        return s.get("id").asLong();
                    }
                }
            }
        }
        throw new AssertionError("storage");
    }

    private static BigDecimal dec(JsonNode n, String f) {
        return n.get(f).decimalValue();
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
