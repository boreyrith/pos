package com.pos.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ChangesTest {

    @Test
    void keepsOnlyFieldsThatChanged() {
        Changes c = new Changes()
                .add("Name", "Latte", "Latte")
                .add("Price", new BigDecimal("2.50"), new BigDecimal("2.75"))
                .add("Barcode", null, "885000")
                .add("Note", "", null);
        assertThat(c.list()).extracting(Changes.Change::field).containsExactly("Price", "Barcode");
        assertThat(c.describe()).isEqualTo("Price: 2.5 → 2.75; Barcode: – → 885000");
    }

    @Test
    void treatsEqualNumbersWithDifferentScaleAsUnchanged() {
        assertThat(new Changes().add("Price", new BigDecimal("2.50"), new BigDecimal("2.5")).isEmpty()).isTrue();
    }
}
