package com.pos.sales;

import java.math.BigDecimal;

/** What checkout needs from promotions and coupons. Marketing implements it ({@code marketing.PromotionService}). */
public interface SalePromotions {

    /**
     * Prices a sale being created: sets each line's promotion, records what each promotion took and
     * reserves its use. Returns the order promotion for {@link PricingCalculator}. Refuses a coupon
     * that can't be used.
     */
    BigDecimal applyToSale(Order order, String couponCode);

    /** Gives back the use of a sale's promotions and coupon when it is cancelled or expires unpaid. */
    void release(Order order);
}
