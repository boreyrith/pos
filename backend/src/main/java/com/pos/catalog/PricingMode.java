package com.pos.catalog;

/** How a service is charged. */
public enum PricingMode {
    /** The price is per job. */
    FIXED,
    /** The price is per hour; the cashier enters the hours worked at checkout. */
    HOURLY
}
