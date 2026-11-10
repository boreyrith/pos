package com.pos.customer;

/** Loyalty tiers, lowest first. Every customer on file is at least Bronze. */
public enum Tier {
    BRONZE, SILVER, GOLD, PLATINUM;

    /** True when this tier is {@code other} or above. */
    public boolean atLeast(Tier other) {
        return ordinal() >= other.ordinal();
    }
}
