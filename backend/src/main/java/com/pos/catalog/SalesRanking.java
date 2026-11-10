package com.pos.catalog;

import java.time.Instant;
import java.util.LinkedHashMap;

/**
 * What sold most in a branch, for the sell screen's top sellers. Sales owns orders and implements
 * this, so the catalog doesn't depend on sales.
 */
public interface SalesRanking {

    /**
     * Units kept (sold, less returned) per catalog entry in the branch since {@code since}, most
     * first, at most {@code max} entries with more than zero.
     */
    LinkedHashMap<Long, Long> unitsSold(Long branchId, Instant since, int max);
}
