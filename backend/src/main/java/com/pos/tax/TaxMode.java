package com.pos.tax;

/** How the store charges VAT. Chosen under Settings > Taxes; applies to every sale from then on. */
public enum TaxMode {
    /** VAT is off: nothing is added or taken out, and receipts show no VAT. */
    NONE,
    /** Listed prices include VAT; it is worked out of the price (gross x rate / (1 + rate)). */
    INCLUSIVE,
    /** Listed prices exclude VAT; it is added on top (taxable amount x rate). "VAT Setup Value". */
    EXCLUSIVE
}
