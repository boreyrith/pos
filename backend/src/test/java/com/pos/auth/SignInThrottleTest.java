package com.pos.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class SignInThrottleTest {

    private final Instant t0 = Instant.parse("2026-11-09T08:00:00Z");

    @Test
    void blocksAnAddressAfterTooManyFailuresUntilTheOldestLeavesTheWindow() {
        SignInThrottle throttle = new SignInThrottle();
        for (int i = 0; i < SignInThrottle.MAX_FAILURES - 1; i++) {
            throttle.failed("203.0.113.9", t0.plusSeconds(i));
        }
        assertThat(throttle.retryAfter("203.0.113.9", t0.plusSeconds(30))).isZero();
        throttle.failed("203.0.113.9", t0.plusSeconds(30));
        // The oldest failure (t0) leaves the 10-minute window at t0 + 600 s.
        assertThat(throttle.retryAfter("203.0.113.9", t0.plusSeconds(31))).isEqualTo(569);
        assertThat(throttle.retryAfter("203.0.113.9", t0.plus(SignInThrottle.WINDOW).plusSeconds(1))).isZero();
    }

    @Test
    void addressesAreCountedSeparately() {
        SignInThrottle throttle = new SignInThrottle();
        for (int i = 0; i < SignInThrottle.MAX_FAILURES; i++) {
            throttle.failed("203.0.113.9", t0);
        }
        assertThat(throttle.retryAfter("203.0.113.9", t0)).isPositive();
        assertThat(throttle.retryAfter("198.51.100.4", t0)).isZero();
        assertThat(throttle.retryAfter(null, t0)).isZero();
    }
}
