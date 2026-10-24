package com.pos.inventory;

public enum TransferStatus {
    /** Sent from another branch; the stock has left the source but not arrived. */
    IN_TRANSIT,
    RECEIVED,
    /** Cancelled while in transit; the stock went back to the source. */
    CANCELLED
}
