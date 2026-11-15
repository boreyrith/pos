package com.pos.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/** Many pollers of one payment share one Bakong question per interval; each payment has its own. */
class AskedRecentlyTest {

    @Test
    void onePaymentIsAskedAboutAtMostOncePerInterval() {
        var asked = new KhqrService.AskedRecently(Duration.ofSeconds(3));
        Instant t = Instant.parse("2026-11-15T10:00:00Z");
        assertThat(asked.askNow(1L, t)).isTrue();
        assertThat(asked.askNow(1L, t.plusSeconds(1))).isFalse();
        assertThat(asked.askNow(1L, t.plusMillis(2999))).isFalse();
        assertThat(asked.askNow(1L, t.plusSeconds(3))).isTrue();
        // Another payment isn't held back by the first.
        assertThat(asked.askNow(2L, t.plusSeconds(1))).isTrue();
    }
}
