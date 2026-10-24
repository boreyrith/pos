package com.pos.sales;

public enum OrderStatus {
    /** Waiting for a KHQR payment; stock is already reserved. */
    PENDING_PAYMENT,
    COMPLETED,
    /** Cancelled or expired before payment; stock was returned. */
    CANCELLED,
    /** Some of it was returned and refunded; the rest stands. */
    PARTIALLY_REFUNDED,
    /** Fully refunded after completion; stock was returned. */
    REFUNDED
}
