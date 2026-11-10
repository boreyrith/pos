package com.pos.tax;

import java.math.BigDecimal;

/**
 * The tax a line is charged under (see {@link TaxService}). Lines with equal keys are taxed together
 * by the pricing engine ({@code sales.PricingCalculator}).
 */
public record TaxKey(Long rateId, String name, BigDecimal rate, boolean included) {
}
