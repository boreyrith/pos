package com.pos.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Your own picture in My account: a photo (add, replace, remove; only you read it; images only, up to
 * 2 MB) or a built-in avatar, one at a time; audited.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProfilePhotoIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** The start of a PNG file: enough for the server to recognise it. */
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    RestClient http;
    String cashier;
    String manager;

    /** status, content type and body. */
    record Res(int status, String type, byte[] body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api").build();
        cashier = login("cashier", "cashier1234");
        manager = login("manager", "manager1234");
        // Start each test without a photo.
        send(HttpMethod.DELETE, "/auth/me/photo", null, cashier);
        send(HttpMethod.DELETE, "/auth/me/photo", null, manager);
    }

    @Test
    void addReplaceAndRemoveYourPhoto() {
        JsonNode before = send(HttpMethod.GET, "/auth/me", null, cashier).json();
        assertThat(before.get("hasPhoto").asBoolean()).isFalse();
        assertThat(send(HttpMethod.GET, "/auth/me/photo", null, cashier).status()).isEqualTo(404);

        Res added = upload(PNG, "me.png", cashier);
        assertThat(added.status()).as(new String(added.body())).isEqualTo(200);
        assertThat(added.json().get("hasPhoto").asBoolean()).isTrue();
        int version = added.json().get("photoVersion").asInt();
        assertThat(version).isGreaterThan(before.get("photoVersion").asInt());

        Res read = send(HttpMethod.GET, "/auth/me/photo", null, cashier);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.type()).startsWith("image/png");
        assertThat(read.body()).isEqualTo(PNG);
        // The type comes from the bytes, not the file name.
        Res replaced = upload(JPEG, "photo.png", cashier);
        assertThat(replaced.json().get("photoVersion").asInt()).isGreaterThan(version);
        assertThat(send(HttpMethod.GET, "/auth/me/photo", null, cashier).type()).startsWith("image/jpeg");

        Res removed = send(HttpMethod.DELETE, "/auth/me/photo", null, cashier);
        assertThat(removed.status()).isEqualTo(200);
        assertThat(removed.json().get("hasPhoto").asBoolean()).isFalse();
        assertThat(send(HttpMethod.GET, "/auth/me/photo", null, cashier).status()).isEqualTo(404);
        long userId = before.get("id").asLong();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM user_photos WHERE user_id = ?").param(userId).query(Long.class).single()).isZero();

        // Each change is in the audit log, against the person.
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'PROFILE_PHOTO_CHANGED' AND entity_id = ?")
                .param(String.valueOf(userId)).query(Long.class).single()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void onlyYouSeeYourPhoto() {
        assertThat(upload(PNG, "me.png", cashier).status()).isEqualTo(200);
        // Another person gets their own (none), never the cashier's; nobody signed out gets anything.
        assertThat(send(HttpMethod.GET, "/auth/me/photo", null, manager).status()).isEqualTo(404);
        assertThat(send(HttpMethod.GET, "/auth/me/photo", null, null).status()).isEqualTo(401);
        assertThat(upload(PNG, "me.png", null).status()).isEqualTo(401);
        assertThat(send(HttpMethod.DELETE, "/auth/me/photo", null, null).status()).isEqualTo(401);
        assertThat(send(HttpMethod.GET, "/auth/me/photo", null, cashier).status()).isEqualTo(200);
    }

    @Test
    void onlyImagesUpTo2Mb() {
        Res svg = upload("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(), "x.svg", cashier);
        assertThat(svg.status()).isEqualTo(400);
        assertThat(svg.json().get("detail").asString()).isEqualTo("The photo must be a PNG, JPEG or WebP image");
        assertThat(upload("not a picture".getBytes(), "x.png", cashier).status()).isEqualTo(400);

        byte[] big = Arrays.copyOf(PNG, 2 * 1024 * 1024 + 1);
        Res tooBig = upload(big, "big.png", cashier);
        assertThat(tooBig.status()).isEqualTo(400);
        assertThat(tooBig.json().get("detail").asString()).isEqualTo("The photo must be 2 MB or smaller");
        assertThat(send(HttpMethod.GET, "/auth/me", null, cashier).json().get("hasPhoto").asBoolean()).isFalse();
    }

    @Test
    void aBuiltInAvatarInsteadOfAPhoto() {
        assertThat(upload(PNG, "me.png", cashier).status()).isEqualTo(200);
        // Choosing an avatar replaces the photo.
        Res fox = send(HttpMethod.PUT, "/auth/me/avatar", Map.of("avatar", "fox"), cashier);
        assertThat(fox.status()).as(new String(fox.body())).isEqualTo(200);
        assertThat(fox.json().get("avatar").asString()).isEqualTo("fox");
        assertThat(fox.json().get("hasPhoto").asBoolean()).isFalse();
        assertThat(send(HttpMethod.GET, "/auth/me/photo", null, cashier).status()).isEqualTo(404);
        assertThat(send(HttpMethod.GET, "/auth/me", null, cashier).json().get("avatar").asString()).isEqualTo("fox");

        // Uploading a photo clears the avatar.
        Res photo = upload(PNG, "me.png", cashier);
        assertThat(photo.json().get("hasPhoto").asBoolean()).isTrue();
        assertThat(photo.json().get("avatar").isNull()).isTrue();

        // Only the app's own avatars; anything else is refused and changes nothing.
        Res unknown = send(HttpMethod.PUT, "/auth/me/avatar", Map.of("avatar", "dragon"), cashier);
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.json().get("detail").asString()).isEqualTo("Pick one of the avatars shown");
        assertThat(send(HttpMethod.PUT, "/auth/me/avatar", Map.of("avatar", "x".repeat(50)), cashier).status()).isEqualTo(400);
        assertThat(send(HttpMethod.GET, "/auth/me", null, cashier).json().get("hasPhoto").asBoolean()).isTrue();

        // None: back to the initials, photo and avatar both gone.
        Res none = send(HttpMethod.PUT, "/auth/me/avatar", new java.util.HashMap<String, Object>(Map.of()), cashier);
        assertThat(none.status()).isEqualTo(200);
        assertThat(none.json().get("hasPhoto").asBoolean()).isFalse();
        assertThat(none.json().get("avatar").isNull()).isTrue();
        // Someone else's picture is untouched by all this.
        assertThat(send(HttpMethod.GET, "/auth/me", null, manager).json().get("avatar").isNull()).isTrue();
        assertThat(send(HttpMethod.PUT, "/auth/me/avatar", Map.of("avatar", "owl"), null).status()).isEqualTo(401);
    }

    private Res upload(byte[] bytes, String name, String token) {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return name;
            }
        });
        var spec = http.put().uri("/auth/me/photo").contentType(MediaType.MULTIPART_FORM_DATA).body(form);
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        return spec.exchange((req, res) -> new Res(res.getStatusCode().value(),
                res.getHeaders().getContentType() == null ? null : res.getHeaders().getContentType().toString(), res.getBody().readAllBytes()));
    }

    private String login(String user, String password) {
        return send(HttpMethod.POST, "/auth/login", Map.of("username", user, "password", password), null).json().get("accessToken").asString();
    }

    private Res send(HttpMethod method, String path, Object body, String token) {
        var spec = http.method(method).uri(path);
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(body));
        }
        return spec.exchange((req, res) -> new Res(res.getStatusCode().value(),
                res.getHeaders().getContentType() == null ? null : res.getHeaders().getContentType().toString(), res.getBody().readAllBytes()));
    }
}
