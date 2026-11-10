package com.pos.sales;

import java.math.BigDecimal;

import com.pos.auth.AuthUser;
import com.pos.customer.Customer;

/**
 * What checkout and returns need from loyalty points and tiers, in the caller's transaction.
 * Loyalty implements it ({@code loyalty.LoyaltyService}).
 */
public interface CustomerRewards {

    /** What {@code points} take off a sale whose other discounts leave {@code base}; refuses what can't be used. */
    BigDecimal checkRedeem(Customer customer, int points, BigDecimal base);

    /** Takes the points used on a saved sale off the customer. */
    void redeem(Order order, AuthUser cashier);

    /** Points for a paid sale. */
    void earn(Order order);

    /** A sale cancelled or expired before payment gives back the points it used. */
    void cancelled(Order order);

    /** A return takes back points earned on what is refunded and gives back points used on it. */
    void returned(Order order, OrderReturn ret, AuthUser user);

    /** Works the customer's tier out again after a sale or a return. */
    void refreshTier(Customer customer);
}
