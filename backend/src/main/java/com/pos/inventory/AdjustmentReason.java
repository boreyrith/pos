package com.pos.inventory;

/** Why stock was adjusted. Each reason fits increases, decreases, or both. */
public enum AdjustmentReason {
    RECEIVED(true, false, "Goods received"),
    FOUND(true, false, "Found"),
    CUSTOMER_RETURN(true, false, "Customer return"),
    DAMAGED(false, true, "Damaged"),
    EXPIRED(false, true, "Expired"),
    LOST(false, true, "Lost"),
    THEFT(false, true, "Theft"),
    INTERNAL_USE(false, true, "Internal use"),
    CORRECTION(true, true, "Correction"),
    COUNT(true, true, "Stock count"),
    OTHER(true, true, "Other");

    private final boolean increase;
    private final boolean decrease;
    private final String label;

    AdjustmentReason(boolean increase, boolean decrease, String label) {
        this.increase = increase;
        this.decrease = decrease;
        this.label = label;
    }

    public boolean fits(int change) {
        return change == 0 || (change > 0 ? increase : decrease);
    }

    public String label() {
        return label;
    }
}
