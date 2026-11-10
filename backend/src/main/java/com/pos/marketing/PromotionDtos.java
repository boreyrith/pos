package com.pos.marketing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import com.pos.sales.DiscountType;
import com.pos.sales.SalesDtos.CartLine;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class PromotionDtos {

    private PromotionDtos() {
    }

    /** Where a promotion stands now. */
    public enum Status { RUNNING, SCHEDULED, ENDED, PAUSED, USED_UP }

    /**
     * @param daysOfWeek ISO days (Monday = 1); empty means every day
     * @param branchIds  where it runs; empty means every branch
     */
    public record PromotionRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @NotNull PromotionKind kind,
            @NotNull DiscountType discountType,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal discountValue,
            PromotionScope scope,
            Set<Long> categoryIds,
            Set<Long> offeringIds,
            @Min(1) @Max(1000) Integer buyQty,
            @Min(1) @Max(1000) Integer getQty,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal minSpend,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal maxDiscount,
            LocalDate startsOn,
            LocalDate endsOn,
            Set<@Min(1) @Max(7) Integer> daysOfWeek,
            LocalTime timeFrom,
            LocalTime timeTo,
            Boolean requiresCode,
            Boolean customersOnly,
            /** Only for customers in this tier or above (null: anyone). */
            com.pos.customer.Tier minTier,
            @Min(1) Integer usageLimit,
            @Min(1) Integer perCustomerLimit,
            @Min(-100) @Max(100) Integer priority,
            Boolean active,
            Set<Long> branchIds) {

        /** Left out, the scope is everything, the switches are off, the priority 0, and the promotion active. */
        PromotionScope scopeOrAll() {
            return scope == null ? PromotionScope.ALL : scope;
        }

        boolean needsCode() {
            return Boolean.TRUE.equals(requiresCode);
        }

        boolean forCustomersOnly() {
            return Boolean.TRUE.equals(customersOnly);
        }

        int priorityOrZero() {
            return priority == null ? 0 : priority;
        }

        boolean isActive() {
            return active == null || active;
        }
    }

    /**
     * @param discountGiven what it has taken off sales so far (cancelled ones not counted)
     * @param orders        sales it took part in
     * @param codes         how many coupon codes it has
     */
    public record PromotionDto(Long id, String name, String description, PromotionKind kind, DiscountType discountType,
                               BigDecimal discountValue, PromotionScope scope, Set<Long> categoryIds, Set<Long> offeringIds,
                               Integer buyQty, Integer getQty, BigDecimal minSpend, BigDecimal maxDiscount, LocalDate startsOn,
                               LocalDate endsOn, List<Integer> daysOfWeek, LocalTime timeFrom, LocalTime timeTo, boolean requiresCode,
                               boolean customersOnly, com.pos.customer.Tier minTier, Integer usageLimit, Integer perCustomerLimit, int usedCount, int priority,
                               boolean active, Set<Long> branchIds, Status status, BigDecimal discountGiven, long orders, long codes,
                               String createdBy, Instant createdAt) {
    }

    public record CouponDto(Long id, String code, Integer maxUses, int usedCount, Long customerId, String customerName,
                            LocalDate expiresOn, boolean active, Instant createdAt) {
        static CouponDto from(CouponCode c) {
            return new CouponDto(c.getId(), c.getCode(), c.getMaxUses(), c.getUsedCount(),
                    c.getCustomer() == null ? null : c.getCustomer().getId(), c.getCustomer() == null ? null : c.getCustomer().getName(),
                    c.getExpiresOn(), c.isActive(), c.getCreatedAt());
        }
    }

    /** One code, typed by staff (e.g. SUMMER10). {@code maxUses} null = any number of times. */
    public record CouponRequest(@NotBlank @Size(min = 3, max = 40) @Pattern(regexp = "[A-Za-z0-9-]+",
            message = "Use letters, digits and dashes only") String code,
                                @Min(1) Integer maxUses, LocalDate expiresOn, Long customerId) {
    }

    /** Many single-use (or {@code maxUses}) codes at once, e.g. for a printed flyer. */
    public record CouponBatchRequest(@Min(1) @Max(1000) int count,
                                     @Size(max = 12) @Pattern(regexp = "[A-Za-z0-9]*", message = "Use letters and digits only") String prefix,
                                     @Min(1) Integer maxUses, LocalDate expiresOn) {
    }

    public record CouponUpdate(boolean active, LocalDate expiresOn) {
    }

    /** A cart to price: its lines, the customer, and a coupon code if one was given. */
    public record EvaluateRequest(@NotEmpty @Size(max = 200) List<@Valid CartLine> items, Long customerId,
                                  @Size(max = 40) String couponCode) {
    }

    public record LinePromo(BigDecimal promoDiscount, Long promotionId, String promotionName) {
    }

    public record AppliedDto(Long promotionId, String name, String code, PromotionKind kind, BigDecimal amount) {
    }

    /** @param message why the code took nothing, or null when it applied */
    public record CouponResult(String code, boolean applied, String message) {
    }

    /** What promotions take off the cart: per line (same order), at order level, and what the coupon did. */
    public record EvaluateResponse(List<LinePromo> lines, BigDecimal orderDiscount, List<AppliedDto> applied, CouponResult coupon) {
    }

    /** Within the percentage limit for % promotions. */
    static boolean percentOk(DiscountType type, BigDecimal value) {
        return type != DiscountType.PERCENT || value.compareTo(BigDecimal.valueOf(100)) <= 0;
    }
}
