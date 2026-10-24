package com.pos.inventory;

public enum AdjustmentKind {
    /** Increases, decreases and corrections, each with a reason. */
    ADJUSTMENT,
    /** A stock count: counted quantities replace the system's. */
    COUNT
}
