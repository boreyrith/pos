package com.pos.config;

import java.lang.management.ManagementFactory;
import java.sql.DatabaseMetaData;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.boot.SpringBootVersion;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.LoginAttempts;
import com.pos.catalog.OfferingService;
import com.pos.settings.SettingsService;

/**
 * How this installation is set up: version, environment, database, sign-in and sessions, sales
 * timers and background jobs. Read-only: these come from the deployment (environment variables and
 * application.yml), so each item names the variable that changes it. Secrets are never shown, only
 * whether they are set.
 */
@RestController
@RequestMapping("/system")
@PreAuthorize("hasAuthority('SETTINGS_VIEW')")
public class SystemController {

    /** {@code env}: the environment variable that sets it; {@code warning}: needs attention before going live. */
    public record Item(String label, String value, String env, String note, boolean warning) {
    }

    public record Group(String title, List<Item> items) {
    }

    private final AppProperties props;
    private final Environment environment;
    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final SettingsService settings;

    public SystemController(AppProperties props, Environment environment, DataSource dataSource, JdbcClient jdbc,
                            SettingsService settings) {
        this.props = props;
        this.environment = environment;
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @GetMapping("/info")
    public List<Group> info() {
        List<Group> groups = new ArrayList<>();
        String[] profiles = environment.getActiveProfiles().length > 0 ? environment.getActiveProfiles() : environment.getDefaultProfiles();
        boolean dev = Arrays.asList(profiles).contains("dev");
        var zone = settings.current().timezone();

        groups.add(new Group("Application", List.of(
                new Item("Version", version(), null, null, false),
                new Item("Environment", String.join(", ", profiles), "SPRING_PROFILES_ACTIVE",
                        dev ? "Development: demo accounts and data are created on an empty database" : null, dev),
                new Item("Server time", ZonedDateTime.now(zone).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")) + " (" + zone + ")",
                        null, "Store time zone, set under General", false),
                new Item("Running for", human(Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime())), null, null, false),
                new Item("Platform", "Java " + Runtime.version().feature() + ", Spring Boot " + SpringBootVersion.getVersion(), null, null, false),
                new Item("Memory", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576 + " MB used of "
                        + Runtime.getRuntime().maxMemory() / 1_048_576 + " MB", null, null, false))));

        groups.add(new Group("Database", List.of(
                new Item("Server", database(), "DB_URL", null, false),
                new Item("Schema version", jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")
                        .query(String.class).optional().map(v -> "V" + v).orElse("unknown"), null, "Updated automatically on start-up", false),
                new Item("Times stored in", "UTC", null, "Shown in the store time zone", false))));

        boolean defaultSecret = !environment.containsProperty("JWT_SECRET");
        boolean secretsKey = props.secretsKey() != null && !props.secretsKey().isBlank();
        groups.add(new Group("Sign-in and sessions", List.of(
                new Item("Session length", human(props.jwt().accessTokenTtl()) + ", renewed while in use", null, null, false),
                new Item("Stay signed in for", human(props.jwt().refreshTokenTtl()), null, "Then the password is asked again", false),
                new Item("Wrong passwords", "Locked for " + human(LoginAttempts.LOCK_FOR) + " after " + LoginAttempts.MAX_FAILURES, null, null, false),
                new Item("Token signing key", defaultSecret ? "Development key" : "Set", "JWT_SECRET",
                        defaultSecret ? "Set your own key before going live" : null, defaultSecret),
                new Item("Secure cookie (HTTPS only)", props.jwt().refreshCookieSecure() ? "On" : "Off", "REFRESH_COOKIE_SECURE",
                        props.jwt().refreshCookieSecure() ? null : "Turn on when served over HTTPS", !props.jwt().refreshCookieSecure()),
                new Item("Encryption key for saved secrets", secretsKey ? "Set" : "Not set", "SECRETS_KEY",
                        secretsKey ? null : "Needed to save the Bakong token", !secretsKey))));

        groups.add(new Group("Sales", List.of(
                new Item("KHQR code valid for", human(props.khqr().expiry()), null, "Unpaid KHQR orders are cancelled after this", false),
                new Item("Held sales expire after", human(props.holds().expiry()), "HOLD_EXPIRY", null, false),
                new Item("Held sale lock released after", human(props.holds().lockTimeout()), "HOLD_LOCK_TIMEOUT",
                        "When a till leaves a resumed sale open", false),
                new Item("Catalog pictures", "PNG, JPEG or WebP, up to " + OfferingService.MAX_IMAGE_BYTES / 1_048_576 + " MB", null, null, false))));

        groups.add(new Group("Background jobs", List.of(
                new Item("Check pending KHQR payments", "Every 30 seconds", null, null, false),
                new Item("Expire held sales, release stuck locks", "Every 5 minutes", null, null, false))));
        return groups;
    }

    private String version() {
        String v = SystemController.class.getPackage().getImplementationVersion();
        return v != null ? v : "Development build";
    }

    private String database() {
        try (var c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            return md.getDatabaseProductName() + " " + md.getDatabaseProductVersion() + " · " + c.getCatalog();
        } catch (Exception e) {
            return "Unavailable";
        }
    }

    /** "15 min", "7 days", "24 h", "3 h 5 min". */
    static String human(Duration d) {
        long min = d.toMinutes();
        if (min < 1) {
            return d.toSeconds() + " s";
        }
        if (min % (60 * 24) == 0 && min >= 60 * 48) {
            return min / (60 * 24) + " days";
        }
        if (min < 60) {
            return min + " min";
        }
        return min / 60 + " h" + (min % 60 == 0 ? "" : " " + min % 60 + " min");
    }
}
