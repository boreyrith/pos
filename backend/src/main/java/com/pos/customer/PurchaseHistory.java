package com.pos.customer;

import com.pos.customer.CustomerService.CustomerStats;

/**
 * A customer's paid orders, totalled, for the customer page. Sales owns orders and implements
 * this, so customers don't depend on sales.
 */
public interface PurchaseHistory {

    /** Orders, spending net of refunds, refunds, and first and last visit, across every branch. */
    CustomerStats stats(Long customerId);
}
