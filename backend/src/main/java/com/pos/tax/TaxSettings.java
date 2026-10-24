package com.pos.tax;

import java.math.BigDecimal;
import java.util.Set;

import com.pos.catalog.OfferingType;

/**
 * The tax configuration in force: mode, the label shown for VAT ("VAT"), the highest rate allowed,
 * and which kinds of catalog entry are taxed.
 */
public record TaxSettings(TaxMode mode, String label, BigDecimal maxRate, Set<OfferingType> scope) {

    public boolean taxes(OfferingType type) {
        return mode != TaxMode.NONE && scope.contains(type);
    }
}
