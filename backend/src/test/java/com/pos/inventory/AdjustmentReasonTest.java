package com.pos.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdjustmentReasonTest {

    @Test
    void reasonsOnlyFitTheirDirection() {
        assertThat(AdjustmentReason.DAMAGED.fits(-3)).isTrue();
        assertThat(AdjustmentReason.DAMAGED.fits(2)).isFalse();
        assertThat(AdjustmentReason.FOUND.fits(1)).isTrue();
        assertThat(AdjustmentReason.FOUND.fits(-1)).isFalse();
        assertThat(AdjustmentReason.CORRECTION.fits(5)).isTrue();
        assertThat(AdjustmentReason.CORRECTION.fits(-5)).isTrue();
    }

    @Test
    void noChangeFitsAnyReason() {
        assertThat(AdjustmentReason.EXPIRED.fits(0)).isTrue();
        assertThat(AdjustmentReason.RECEIVED.fits(0)).isTrue();
    }
}
