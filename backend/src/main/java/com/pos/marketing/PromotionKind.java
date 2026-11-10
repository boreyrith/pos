package com.pos.marketing;

/** What a promotion does. */
public enum PromotionKind {
    /** % or amount off each qualifying line (an amount is per unit). */
    ITEM_DISCOUNT,
    /** For every buy + get qualifying units, the cheapest get units are % off (100% = free). */
    BUY_X_GET_Y,
    /** % or amount off the whole order. */
    ORDER_DISCOUNT;

    public boolean onLines() {
        return this != ORDER_DISCOUNT;
    }
}
