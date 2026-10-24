package com.pos.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.pos.common.ApiException;

class KhqrSettingsControllerTest {

    private static String jwt(String payload) {
        Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
        return e.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + e.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    @Test
    void readsTheTokenExpiry() {
        assertThat(KhqrSettingsController.tokenExpiry(jwt("{\"data\":{\"id\":\"x\"},\"iat\":1,\"exp\":1800000000}")))
                .isEqualTo(Instant.ofEpochSecond(1800000000));
        assertThat(KhqrSettingsController.tokenExpiry(jwt("{\"iat\":1}"))).isNull();
        assertThat(KhqrSettingsController.tokenExpiry("not-a-jwt")).isNull();
    }

    @Test
    void cleansPastedTokens() {
        String t = jwt("{\"exp\":1}");
        assertThat(KhqrSettingsController.cleanToken("  Bearer " + t + "\n")).isEqualTo(t);
        assertThatThrownBy(() -> KhqrSettingsController.cleanToken("short")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> KhqrSettingsController.cleanToken("abc def ghi jkl mno pqr stu")).isInstanceOf(ApiException.class);
        assertThat(KhqrSettingsController.hint(t)).isEqualTo("ture");
    }

    @Test
    void acceptsOnlyHttpsAddresses() {
        assertThat(KhqrSettingsController.normalizeUrl(" https://api-bakong.nbc.gov.kh/ ")).isEqualTo("https://api-bakong.nbc.gov.kh");
        assertThat(KhqrSettingsController.normalizeUrl("https://sit-api-bakong.nbc.gov.kh")).isEqualTo("https://sit-api-bakong.nbc.gov.kh");
        assertThat(KhqrSettingsController.normalizeUrl("http://localhost:9000")).isEqualTo("http://localhost:9000");
        for (String bad : new String[] {"http://api-bakong.nbc.gov.kh", "ftp://x", "api-bakong.nbc.gov.kh", "https://u:p@host", "https://h?x=1"}) {
            assertThatThrownBy(() -> KhqrSettingsController.normalizeUrl(bad)).as(bad).isInstanceOf(ApiException.class);
        }
    }
}
