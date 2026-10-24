package com.pos.inventory;

/** Why stock changed. */
public enum MovementType {
    /** Stock carried over when branches were introduced, or entered when an entry was created. */
    OPENING,
    SALE,
    /** An unpaid order was cancelled or its KHQR code expired. */
    SALE_RETURN,
    REFUND,
    /** Goods received from a vendor against a purchase order. */
    PURCHASE,
    RECEIVE,
    REMOVE,
    COUNT,
    /** A stock adjustment added stock (found, correction...). The reason says why. */
    ADJUSTMENT_IN,
    /** A stock adjustment took stock away (damaged, expired, lost...). The reason says why. */
    ADJUSTMENT_OUT,
    TRANSFER_OUT,
    TRANSFER_IN,
    /** A transfer was cancelled while in transit; stock went back to the source. */
    TRANSFER_RETURN
}
