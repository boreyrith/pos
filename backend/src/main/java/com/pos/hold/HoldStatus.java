package com.pos.hold;

public enum HoldStatus {
    /** Waiting to be resumed by any till in the branch. */
    HELD,
    /** Resumed and being completed by one cashier (locked to them). */
    IN_USE,
    /** Paid: became an order. */
    COMPLETED,
    /** Called off, with a reason. */
    VOIDED,
    /** Nobody resumed it in time. */
    EXPIRED;

    public boolean open() {
        return this == HELD || this == IN_USE;
    }
}
