package com.pos.purchasing;

public enum PurchaseStatus {
    /** Being prepared; lines can still change. */
    DRAFT,
    /** Placed with the vendor; waiting for delivery. */
    ORDERED,
    PARTIALLY_RECEIVED,
    /** Everything arrived, or the rest was written off by closing the order. */
    RECEIVED,
    /** Called off before anything arrived. */
    CANCELLED;

    public boolean receivable() {
        return this == ORDERED || this == PARTIALLY_RECEIVED;
    }
}
