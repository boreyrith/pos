package com.pos.catalog;

public enum OfferingType {
    /** Finished goods sold to customers. Stock is tracked. */
    PRODUCT,
    /** Billable work. No stock of its own, but may use up items. */
    SERVICE,
    /** Parts, materials and accessories. Stock is tracked; sold on its own only when sellable. */
    ITEM,
    /** A bundle of other entries with its own price. No stock of its own; uses its contents' stock. */
    PACKAGE;

    public boolean tracksStock() {
        return this == PRODUCT || this == ITEM;
    }
}
