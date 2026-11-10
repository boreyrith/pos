package com.pos.accounting;

/**
 * The accounts automatic postings use, by their system key (seeded in V31__accounting.sql). Each
 * branch's cash drawer is {@code CASH:<branch id>}, created when a new branch first needs it.
 */
public final class SystemAccount {

    public static final String KHQR = "KHQR";
    public static final String BANK = "BANK";
    public static final String INVENTORY = "INVENTORY";
    public static final String VAT_INPUT = "VAT_INPUT";
    public static final String PAYABLES = "PAYABLES";
    public static final String GOODS_NOT_INVOICED = "GOODS_NOT_INVOICED";
    public static final String VAT_OUTPUT = "VAT_OUTPUT";
    public static final String OPENING = "OPENING";
    public static final String RETAINED = "RETAINED";
    public static final String SALES = "SALES";
    public static final String COST_OF_SALES = "COST_OF_SALES";
    public static final String STOCK_ADJUSTMENTS = "STOCK_ADJUSTMENTS";
    public static final String PRICE_DIFFERENCES = "PRICE_DIFFERENCES";
    public static final String REVALUATION = "REVALUATION";
    public static final String GENERAL_EXPENSES = "GENERAL_EXPENSES";

    private SystemAccount() {
    }

    public static String cash(Long branchId) {
        return "CASH:" + branchId;
    }
}
