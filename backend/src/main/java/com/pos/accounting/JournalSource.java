package com.pos.accounting;

/** What made a journal entry. All but MANUAL, OPENING and REVERSAL are posted automatically. */
public enum JournalSource {
    SALE("Sale"),
    SALE_RETURN("Return"),
    GOODS_RECEIPT("Delivery"),
    VENDOR_INVOICE("Vendor invoice"),
    VENDOR_INVOICE_VOID("Voided vendor invoice"),
    VENDOR_PAYMENT("Vendor payment"),
    STOCK_ADJUSTMENT("Stock adjustment"),
    OPENING_STOCK("Opening stock"),
    REVALUATION("Cost change"),
    OTHER_INCOME("Other income"),
    OTHER_EXPENSE("Other expense"),
    MANUAL("Journal entry"),
    OPENING("Opening balances"),
    REVERSAL("Reversal");

    private final String label;

    JournalSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Entries an accountant may reverse. The others are corrected through their documents (a return, a void...). */
    public boolean reversible() {
        return this == MANUAL || this == OPENING;
    }
}
