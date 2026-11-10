package com.pos.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pos.common.ApiException;
import com.pos.common.Money;
import com.pos.tax.TaxKey;

/**
 * Order totals with named tax rates. A line costs listed price x quantity (x hours for hourly
 * services); the listed price either includes its tax or excludes it. A promotion on the line (an
 * amount worked out by the promotion engine) comes off first, then the line's own item discount
 * (% of what is left, or an amount). At order level, in this order: the order promotion (coupon or
 * automatic, an amount), the cashier's order discount (worked out on what is left), then loyalty
 * points (an amount). Together they are shared over the tax groups in proportion to their value.
 * Then, per group (same rate, same included/added):
 * an included tax is taken out of the discounted amount (tax = amount x r / (1 + r)), an added tax
 * goes on top of it (tax = amount x r). Rounding is half-up to cents.
 * <p>Each group's discount and tax are then shared over its lines by value, the last line taking
 * the leftover cent, so line amounts add up exactly to the order's. Returns refund from these.
 * <p>The web client mirrors this in lib/pricing.ts; both are checked against
 * src/test/resources/pricing-cases.json.
 */
public final class PricingCalculator {

    /**
     * @param hours         hours worked for an hourly service, otherwise null
     * @param discountType  an item discount on this line (% of the line after its promotion, or an amount), or null
     * @param promoDiscount what a promotion takes off the line, in money (null or zero for none); at most the line
     */
    public record Line(BigDecimal unitPrice, int quantity, BigDecimal hours, TaxKey tax, DiscountType discountType,
                       BigDecimal discountValue, BigDecimal promoDiscount) {

        public Line(BigDecimal unitPrice, int quantity, BigDecimal hours, TaxKey tax, DiscountType discountType,
                    BigDecimal discountValue) {
            this(unitPrice, quantity, hours, tax, discountType, discountValue, null);
        }

        public Line(BigDecimal unitPrice, int quantity, BigDecimal hours, TaxKey tax) {
            this(unitPrice, quantity, hours, tax, null, null, null);
        }

        BigDecimal total() {
            BigDecimal units = BigDecimal.valueOf(quantity);
            if (hours != null) {
                units = units.multiply(hours);
            }
            return Money.round(unitPrice.multiply(units));
        }

        /** The promotion's discount in money, never more than the line. */
        public BigDecimal promo() {
            if (promoDiscount == null || promoDiscount.signum() <= 0) {
                return Money.round(BigDecimal.ZERO);
            }
            return Money.round(promoDiscount.min(total()));
        }

        /** The item discount in money: a % of the line after its promotion, or an amount (at most what is left). */
        public BigDecimal itemDiscount() {
            return discount(total().subtract(promo()), discountType, discountValue);
        }

        /** The line after its promotion and item discount; the order-level discounts and tax work from this. */
        BigDecimal afterItemDiscount() {
            return total().subtract(promo()).subtract(itemDiscount());
        }

        /** The line as priced, before any discount. */
        public BigDecimal gross() {
            return total();
        }
    }

    /**
     * A line's share of the order: {@code promoDiscount} is its promotion, {@code itemDiscount} its
     * own discount, {@code discount} both plus its share of the order-level discounts; {@code net} is
     * after all of them, without tax; {@code paid} is what the customer pays for it (net + tax).
     */
    public record LineSplit(BigDecimal total, BigDecimal itemDiscount, BigDecimal discount, BigDecimal net, BigDecimal tax,
                            BigDecimal paid, BigDecimal promoDiscount) {
    }

    /** One tax group: {@code base} is the net amount it is charged on, after discount. */
    public record TaxLine(Long rateId, String name, BigDecimal rate, boolean included, BigDecimal base, BigDecimal tax) {
    }

    /**
     * @param subtotal           listed prices x quantities (with included taxes inside), before any discount
     * @param itemDiscount       the item discounts on all lines
     * @param discount           the cashier's order discount, worked out on what is left after the order promotion
     * @param taxAdded           taxes added on top of exclusive prices
     * @param taxIncluded        taxes already inside inclusive prices
     * @param total              subtotal - promoDiscount - itemDiscount - orderPromoDiscount - discount - pointsDiscount + taxAdded
     * @param lines              per input line, in the same order
     * @param promoDiscount      the promotions on all lines
     * @param orderPromoDiscount the order promotion (coupon or automatic)
     * @param pointsDiscount     loyalty points redeemed, in money
     */
    public record Totals(BigDecimal subtotal, BigDecimal itemDiscount, BigDecimal discount, BigDecimal taxAdded,
                         BigDecimal taxIncluded, BigDecimal total, List<TaxLine> taxes, List<LineSplit> lines,
                         BigDecimal promoDiscount, BigDecimal orderPromoDiscount, BigDecimal pointsDiscount) {

        public BigDecimal tax() {
            return taxAdded.add(taxIncluded);
        }

        /** Net amount (after discount, before tax) of lines that are charged tax. */
        public BigDecimal taxableAmount() {
            return taxes.stream().filter(t -> t.rate().signum() > 0).map(TaxLine::base).reduce(Money.round(BigDecimal.ZERO), BigDecimal::add);
        }

        /** Net amount of lines charged no tax (exempt, out of scope, or No VAT mode). */
        public BigDecimal nonTaxableAmount() {
            return taxes.stream().filter(t -> t.rate().signum() == 0).map(TaxLine::base).reduce(Money.round(BigDecimal.ZERO), BigDecimal::add);
        }
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PricingCalculator() {
    }

    public static Totals calculate(List<Line> lines, DiscountType discountType, BigDecimal discountValue) {
        return calculate(lines, discountType, discountValue, null, null);
    }

    /**
     * @param orderPromo the order promotion in money (null for none); capped at what is left after line discounts
     * @param points     loyalty points redeemed, in money (null for none); capped at what is left after everything else
     */
    public static Totals calculate(List<Line> lines, DiscountType discountType, BigDecimal discountValue, BigDecimal orderPromo,
                                   BigDecimal points) {
        // Line promotions and item discounts first; the order-level discounts are then shared over what is left, by value.
        Map<TaxKey, BigDecimal> gross = new LinkedHashMap<>();
        BigDecimal subtotal = Money.round(BigDecimal.ZERO);
        BigDecimal promoDiscount = Money.round(BigDecimal.ZERO);
        BigDecimal itemDiscount = Money.round(BigDecimal.ZERO);
        for (Line line : lines) {
            subtotal = subtotal.add(line.total());
            promoDiscount = promoDiscount.add(line.promo());
            itemDiscount = itemDiscount.add(line.itemDiscount());
            gross.merge(line.tax(), line.afterItemDiscount(), BigDecimal::add);
        }
        BigDecimal afterItems = subtotal.subtract(promoDiscount).subtract(itemDiscount);
        BigDecimal orderPromoDiscount = capped(orderPromo, afterItems);
        BigDecimal manual = discount(afterItems.subtract(orderPromoDiscount), discountType, discountValue);
        BigDecimal pointsDiscount = capped(points, afterItems.subtract(orderPromoDiscount).subtract(manual));
        // Everything taken off at order level, shared over the tax groups and lines together.
        BigDecimal discount = orderPromoDiscount.add(manual).add(pointsDiscount);

        List<TaxLine> taxes = new ArrayList<>();
        BigDecimal added = Money.round(BigDecimal.ZERO);
        BigDecimal included = Money.round(BigDecimal.ZERO);
        BigDecimal discountLeft = discount;
        LineSplit[] splits = new LineSplit[lines.size()];
        int i = 0;
        for (var group : gross.entrySet()) {
            TaxKey key = group.getKey();
            // Round each group's share; the last group takes what is left so shares add up exactly.
            BigDecimal share = ++i == gross.size() ? discountLeft
                    : afterItems.signum() == 0 ? BigDecimal.ZERO
                    : Money.round(discount.multiply(group.getValue()).divide(afterItems, 10, RoundingMode.HALF_UP));
            discountLeft = discountLeft.subtract(share);
            BigDecimal amount = group.getValue().subtract(share);

            BigDecimal tax;
            BigDecimal base;
            if (key.included()) {
                tax = Money.round(amount.multiply(key.rate()).divide(BigDecimal.ONE.add(key.rate()), 10, RoundingMode.HALF_UP));
                base = amount.subtract(tax);
                included = included.add(tax);
            } else {
                base = amount;
                tax = Money.round(amount.multiply(key.rate()));
                added = added.add(tax);
            }
            taxes.add(new TaxLine(key.rateId(), key.name(), key.rate(), key.included(), base, tax));
            splitGroup(lines, key, group.getValue(), share, tax, splits);
        }
        BigDecimal total = afterItems.subtract(discount).add(added);
        return new Totals(subtotal, itemDiscount, manual, added, included, total, taxes, List.of(splits), promoDiscount,
                orderPromoDiscount, pointsDiscount);
    }

    /** A money amount between zero and {@code most}. */
    private static BigDecimal capped(BigDecimal amount, BigDecimal most) {
        if (amount == null || amount.signum() <= 0 || most.signum() <= 0) {
            return Money.round(BigDecimal.ZERO);
        }
        return Money.round(amount.min(most));
    }

    /** Shares a group's discount and tax over its lines by value; the group's last line takes the rest. */
    private static void splitGroup(List<Line> lines, TaxKey key, BigDecimal groupTotal, BigDecimal groupDiscount,
                                   BigDecimal groupTax, LineSplit[] out) {
        int last = -1;
        for (int j = 0; j < lines.size(); j++) {
            if (lines.get(j).tax().equals(key)) {
                last = j;
            }
        }
        BigDecimal discountLeft = groupDiscount;
        BigDecimal taxLeft = groupTax;
        for (int j = 0; j < lines.size(); j++) {
            Line line = lines.get(j);
            if (!line.tax().equals(key)) {
                continue;
            }
            BigDecimal t = line.afterItemDiscount();
            BigDecimal d;
            BigDecimal tax;
            if (j == last) {
                d = discountLeft;
                tax = taxLeft;
            } else if (groupTotal.signum() == 0) {
                d = Money.round(BigDecimal.ZERO);
                tax = Money.round(BigDecimal.ZERO);
            } else {
                d = Money.round(groupDiscount.multiply(t).divide(groupTotal, 10, RoundingMode.HALF_UP));
                tax = Money.round(groupTax.multiply(t).divide(groupTotal, 10, RoundingMode.HALF_UP));
            }
            discountLeft = discountLeft.subtract(d);
            taxLeft = taxLeft.subtract(tax);
            BigDecimal net = key.included() ? t.subtract(d).subtract(tax) : t.subtract(d);
            BigDecimal item = line.itemDiscount();
            BigDecimal promo = line.promo();
            out[j] = new LineSplit(line.total(), item, promo.add(item).add(d), net, tax, net.add(tax), promo);
        }
    }

    private static BigDecimal discount(BigDecimal subtotal, DiscountType type, BigDecimal value) {
        if (type == null || value == null || value.signum() == 0) {
            return Money.round(BigDecimal.ZERO);
        }
        if (value.signum() < 0) {
            throw ApiException.badRequest("Discount cannot be negative");
        }
        return switch (type) {
            case PERCENT -> {
                if (value.compareTo(HUNDRED) > 0) {
                    throw ApiException.badRequest("Discount cannot be more than 100%");
                }
                yield Money.round(subtotal.multiply(value).divide(HUNDRED, 10, RoundingMode.HALF_UP));
            }
            case AMOUNT -> Money.round(value.min(subtotal));
        };
    }
}
