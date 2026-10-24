package com.pos.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.pos.common.Money;

/**
 * What returning some units of a sold line refunds. Each unit is worth its share of what the line
 * was paid (after discount, with tax), and the tax inside that share is the same share of the
 * line's tax, so refunds take back exactly the tax charged. The line's last units refund whatever
 * is left, which keeps the totals exact however a line is split over returns.
 */
public final class RefundSplit {

    public record Share(BigDecimal amount, BigDecimal tax) {
    }

    private RefundSplit() {
    }

    public static Share share(OrderItem line, int qty) {
        return share(line.getLinePaid(), line.getLineTax(), line.getQuantity(), line.getRefundedQty(),
                line.getRefundedAmount(), line.getRefundedTax(), qty);
    }

    /**
     * @param paid           what the line was paid, tax included
     * @param tax            the line's tax
     * @param quantity       units sold
     * @param refundedQty    units already returned
     * @param refundedAmount already refunded for the line
     * @param refundedTax    tax already refunded for the line
     * @param qty            units being returned now
     */
    public static Share share(BigDecimal paid, BigDecimal tax, int quantity, int refundedQty, BigDecimal refundedAmount,
                              BigDecimal refundedTax, int qty) {
        if (qty <= 0 || refundedQty + qty > quantity) {
            throw new IllegalArgumentException("Can't return " + qty + " of " + (quantity - refundedQty));
        }
        if (refundedQty + qty == quantity) {
            return new Share(paid.subtract(refundedAmount), tax.subtract(refundedTax));
        }
        // Cumulative rounding: the amount for units 1..n is round(paid x n / quantity), so returning
        // 1 + 1 + 1 refunds the same as returning 3 at once.
        BigDecimal q = BigDecimal.valueOf(quantity);
        BigDecimal upTo = Money.round(paid.multiply(BigDecimal.valueOf(refundedQty + qty)).divide(q, 10, RoundingMode.HALF_UP));
        BigDecimal taxUpTo = Money.round(tax.multiply(BigDecimal.valueOf(refundedQty + qty)).divide(q, 10, RoundingMode.HALF_UP));
        return new Share(upTo.subtract(refundedAmount), taxUpTo.subtract(refundedTax));
    }
}
