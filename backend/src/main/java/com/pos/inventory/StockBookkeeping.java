package com.pos.inventory;

import java.math.BigDecimal;

import com.pos.catalog.Offering;

/**
 * The journal entries for stock that changes value outside a sale or a delivery, made in the
 * caller's transaction. Accounting implements it ({@code accounting.Postings}).
 */
public interface StockBookkeeping {

    /** Stock entered with a new catalog entry. */
    void openingStock(Offering o, Long branchId, int qty, Long userId);

    /** A catalog cost change: what is on hand is revalued. */
    void costChanged(Offering o, BigDecimal before, BigDecimal after, Long userId);

    /** A stock adjustment or count. */
    void stockAdjustment(StockAdjustment a, Long userId);
}
