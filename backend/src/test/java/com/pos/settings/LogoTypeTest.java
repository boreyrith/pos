package com.pos.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class LogoTypeTest {

    @Test
    void recognisesImagesByTheirFirstBytes() {
        assertThat(SettingsController.sniffImageType(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0})).isEqualTo("image/png");
        assertThat(SettingsController.sniffImageType(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isEqualTo("image/jpeg");
        assertThat(SettingsController.sniffImageType("RIFF0000WEBPVP8 ".getBytes(StandardCharsets.US_ASCII))).isEqualTo("image/webp");
    }

    @Test
    void refusesSvgAndAnythingElse() {
        assertThat(SettingsController.sniffImageType("<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(SettingsController.sniffImageType("GIF89a......".getBytes(StandardCharsets.US_ASCII))).isNull();
        assertThat(SettingsController.sniffImageType(new byte[0])).isNull();
    }
}
