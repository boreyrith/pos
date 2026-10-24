package com.pos.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.pos.config.AppProperties;

class SecretBoxTest {

    private static AppProperties props(String secretsKey, String jwtSecret) {
        return new AppProperties(new AppProperties.Jwt(jwtSecret, Duration.ofMinutes(15), Duration.ofDays(7), false, Duration.ofSeconds(30)), null, null,
                secretsKey, null);
    }

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void roundTripsAndNeverStoresThePlainText() {
        SecretBox box = new SecretBox(props(KEY, "x"));
        byte[] sealed = box.encrypt("eyJhbGciOi.secret-token.sig");
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("secret-token");
        assertThat(box.decrypt(sealed)).isEqualTo("eyJhbGciOi.secret-token.sig");
        // A fresh IV each time.
        assertThat(box.encrypt("same")).isNotEqualTo(box.encrypt("same"));
    }

    @Test
    void anotherKeyCannotReadIt() {
        byte[] sealed = new SecretBox(props(KEY, "x")).encrypt("token");
        String other = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
        assertThat(new SecretBox(props(other, "x")).decrypt(sealed)).isNull();
        assertThat(new SecretBox(props(KEY, "x")).decrypt(null)).isNull();
    }

    @Test
    void derivesAKeyFromTheJwtSecretWhenNoneIsSet() {
        byte[] sealed = new SecretBox(props("", "jwt-secret")).encrypt("token");
        assertThat(new SecretBox(props(null, "jwt-secret")).decrypt(sealed)).isEqualTo("token");
    }

    @Test
    void rejectsAKeyOfTheWrongSize() {
        assertThatThrownBy(() -> new SecretBox(props(Base64.getEncoder().encodeToString(new byte[16]), "x")))
                .isInstanceOf(IllegalStateException.class);
    }
}
