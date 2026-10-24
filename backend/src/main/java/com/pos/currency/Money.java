package com.pos.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** A currency with the rate in force, enough to convert and round. Base has rate 1. */
public record Money(String code, String symbol, int decimals, BigDecimal cashRounding, BigDecimal rate, boolean base) {

    /** A base amount in this currency, rounded to its decimals. */
    public BigDecimal fromBase(BigDecimal baseAmount) {
        return baseAmount.multiply(rate).setScale(decimals, RoundingMode.HALF_UP);
    }

    /** An amount in this currency, in the base currency (kept precise; round where shown). */
    public BigDecimal toBase(BigDecimal amount) {
        return amount.divide(rate, 6, RoundingMode.HALF_UP);
    }

    /** Rounds to what can be handed over in cash, e.g. 50,696.75 riel becomes 50,700. */
    public BigDecimal roundCash(BigDecimal amount) {
        return amount.divide(cashRounding, 0, RoundingMode.HALF_UP).multiply(cashRounding).setScale(decimals, RoundingMode.HALF_UP);
    }

    /** Whether an amount needs more decimals than the currency has, e.g. 10.5 riel. */
    public boolean hasTooManyDecimals(BigDecimal amount) {
        return amount.stripTrailingZeros().scale() > decimals;
    }
}
