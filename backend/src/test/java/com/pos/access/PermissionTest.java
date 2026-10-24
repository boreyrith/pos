package com.pos.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class PermissionTest {

    @Test
    void impliedPermissionsAreAdded() {
        assertThat(Permission.withImplied(EnumSet.of(Permission.ORDERS_REFUND)))
                .containsExactlyInAnyOrder(Permission.ORDERS_REFUND, Permission.ORDERS_VIEW);
        assertThat(Permission.withImplied(EnumSet.of(Permission.SETTINGS_CURRENCY)))
                .containsExactlyInAnyOrder(Permission.SETTINGS_CURRENCY, Permission.CURRENCY_RATES);
        assertThat(Permission.withImplied(EnumSet.noneOf(Permission.class))).isEmpty();
    }

    @Test
    void superAdminHasEverything() {
        Role r = new Role();
        r.setSuperAdmin(true);
        assertThat(r.effectivePermissions()).containsExactlyInAnyOrder(Permission.values());
    }

    /** Every hasAuthority('X') in the code names a real permission, so a typo can't lock an endpoint. */
    @Test
    void everyCheckedAuthorityExists() throws IOException {
        Pattern call = Pattern.compile("has(?:Any)?Authority\\(([^)]*)\\)");
        Pattern name = Pattern.compile("'([A-Z_]+)'");
        int checks = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = call.matcher(Files.readString(f));
                while (m.find()) {
                    Matcher n = name.matcher(m.group(1));
                    while (n.find()) {
                        checks++;
                        assertThat(Permission.valueOf(n.group(1))).as(f + ": " + n.group(1)).isNotNull();
                    }
                }
            }
        }
        assertThat(checks).isGreaterThan(40);
    }

    @Test
    void noEndpointStillChecksOldRoles() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(f)).as(f.toString()).doesNotContain("hasRole(").doesNotContain("hasAnyRole(");
            }
        }
    }
}
