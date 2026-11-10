package com.pos.marketing;

import java.math.BigDecimal;
import java.util.Set;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Who belongs to a segment. Every rule left out matches everyone; the rules given must all hold.
 * Spend is what the customer paid after refunds; days count back from now.
 *
 * @param boughtWithinDays  bought at least once in the last N days
 * @param notBoughtForDays  has bought before, but not in the last N days ("lapsed")
 * @param joinedWithinDays  added as a customer in the last N days
 * @param categoryIds       has bought something from any of these categories
 * @param minTier           in this loyalty tier or above
 */
public record SegmentRules(
        @DecimalMin("0") BigDecimal minSpent,
        @DecimalMin("0") BigDecimal maxSpent,
        @Min(0) Integer minOrders,
        @Min(0) Integer maxOrders,
        @Min(1) @Max(3650) Integer boughtWithinDays,
        @Min(1) @Max(3650) Integer notBoughtForDays,
        @Min(1) @Max(3650) Integer joinedWithinDays,
        Integer minPoints,
        Boolean hasPhone,
        Boolean hasEmail,
        Set<Long> categoryIds,
        com.pos.customer.Tier minTier) {
}
