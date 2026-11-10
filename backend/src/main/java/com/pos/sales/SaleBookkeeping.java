package com.pos.sales;

import java.util.Map;

/**
 * The journal entries for sales and returns, made in the caller's transaction so a sale and its
 * entry are saved together or not at all. Accounting implements it ({@code accounting.Postings}).
 */
public interface SaleBookkeeping {

    /** A completed sale. {@code stock}: units it took, per catalog entry. */
    void sale(Order order, Map<Long, Integer> stock, Long userId);

    /** A return. {@code stockBack}: units that came back, per catalog entry. */
    void saleReturn(Order order, OrderReturn ret, Map<Long, Integer> stockBack, Long userId);
}
