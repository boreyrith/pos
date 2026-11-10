package com.pos.accounting;

/**
 * How much of accounting the store uses. Both post sales, purchases and stock the same way; NORMAL
 * leaves out what needs accounting knowledge: opening balances, manual journal entries and
 * reversals, the balance sheet and the trial balance.
 */
public enum AccountingMode {
    NORMAL, FULL
}
