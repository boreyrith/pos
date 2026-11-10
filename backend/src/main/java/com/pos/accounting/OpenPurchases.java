package com.pos.accounting;

import java.math.BigDecimal;
import java.util.List;

/**
 * What purchasing knows about money owed to vendors, for the suggested opening balances. Purchasing
 * owns its documents and implements this, so accounting doesn't depend on purchasing.
 */
public interface OpenPurchases {

    record VendorOwed(Long vendorId, String vendorName, BigDecimal owed) {
    }

    /** What is left to pay on unpaid and partly paid vendor invoices, per vendor (more than zero), by name. */
    List<VendorOwed> owedByVendor();

    /** Deliveries received at cost, less what was invoiced against each purchase order (never below zero). */
    BigDecimal receivedNotInvoiced();
}
