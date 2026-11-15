package com.pos.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mobile app's download page through the API: open to everyone to read and download; set up under
 * Settings > Mobile app by those who change company settings; only real APKs and store links accepted.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MobileAppIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    RestClient http;
    String admin;
    String manager;

    /** status, a header (Content-Type or Content-Disposition as asked), the body. */
    record Res(int status, HttpHeaders headers, byte[] body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api").build();
        admin = login("admin", "admin1234");
        manager = login("manager", "manager1234");
    }

    @Test
    void adminsSetItUpAndEveryoneDownloadsIt() throws IOException {
        // Readable by anyone, without an APK at first.
        JsonNode page = send(HttpMethod.GET, "/mobile-app", null, null).json();
        assertThat(page.get("androidMinimum").asString()).isEqualTo("7.0");
        assertThat(page.get("iosMinimum").asString()).isEqualTo("15.0");
        assertThat(send(HttpMethod.GET, "/mobile-app/apk", null, null).status()).isEqualTo(404);

        // Only those who change company settings change it; store links must point at the store.
        assertThat(send(HttpMethod.PUT, "/settings/mobile-app", settings("https://play.google.com/store/apps/details?id=x"), manager).status())
                .isEqualTo(403);
        assertThat(send(HttpMethod.PUT, "/settings/mobile-app", settings("https://evil.example.com/pos.apk"), admin).status()).isEqualTo(400);
        assertThat(send(HttpMethod.PUT, "/settings/mobile-app", settings("https://play.google.com/store/apps/details?id=com.pos"), admin).status())
                .isEqualTo(200);

        // Only an Android package is accepted.
        assertThat(upload("not an app".getBytes(), admin).status()).isEqualTo(400);
        assertThat(upload(zip("readme.txt"), admin).status()).isEqualTo(400);
        assertThat(upload(apk(), manager).status()).isEqualTo(403);
        Res uploaded = upload(apk(), admin);
        assertThat(uploaded.status()).isEqualTo(200);
        JsonNode apk = uploaded.json().get("app").get("apk");
        assertThat(apk.get("fileName").asString()).isEqualTo("POS-1.2.0.apk");
        assertThat(apk.get("sha256").asString()).hasSize(64);

        // Anyone downloads it, as an Android package, and each download counts.
        Res download = send(HttpMethod.GET, "/mobile-app/apk", null, null);
        assertThat(download.status()).isEqualTo(200);
        assertThat(download.headers().getContentType().toString()).isEqualTo("application/vnd.android.package-archive");
        assertThat(download.headers().getContentDisposition().getFilename()).isEqualTo("POS-1.2.0.apk");
        assertThat(download.body()).isEqualTo(apk());
        send(HttpMethod.GET, "/mobile-app/apk", null, null);
        assertThat(send(HttpMethod.GET, "/settings/mobile-app", null, manager).json().get("apkDownloads").asInt()).isEqualTo(2);

        // Removed: nothing to download.
        assertThat(send(HttpMethod.DELETE, "/settings/mobile-app/apk", null, admin).status()).isEqualTo(200);
        assertThat(send(HttpMethod.GET, "/mobile-app/apk", null, null).status()).isEqualTo(404);
        assertThat(send(HttpMethod.GET, "/mobile-app", null, null).json().get("apk").isNull()).isTrue();
    }

    private Map<String, Object> settings(String play) {
        var m = new HashMap<String, Object>();
        m.put("versionName", "1.2.0");
        m.put("releaseNotes", "Online orders on the till.");
        m.put("playStoreUrl", play);
        return m;
    }

    /** A minimal APK: a zip with the manifest and code an Android package has. */
    private static byte[] apk() throws IOException {
        return zip("AndroidManifest.xml", "classes.dex", "resources.arsc");
    }

    private static byte[] zip(String... names) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (String n : names) {
                zip.putNextEntry(new ZipEntry(n));
                zip.write(("content of " + n).getBytes());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private Res upload(byte[] bytes, String token) {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "app-release.apk";
            }
        });
        return http.put().uri("/settings/mobile-app/apk").header("Authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                .exchange((req, res) -> new Res(res.getStatusCode().value(), res.getHeaders(), res.getBody().readAllBytes()));
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
        return spec.exchange((req, res) -> new Res(res.getStatusCode().value(), res.getHeaders(), res.getBody().readAllBytes()));
    }
}
