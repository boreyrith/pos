package com.pos.marketing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pos.common.Money;
import com.pos.sales.DiscountType;
import com.pos.sales.PricingCalculator;
import com.pos.tax.TaxKey;

/**
 * Works out what promotions take off a cart. Pure: the caller has already decided which promotions
 * are running (dates, hours, branch, customer, usage limits); this decides what they are worth for
 * these lines.
 * <p>Lines first. A line can take one line promotion. The coupon's goes first (the customer asked
 * for it), then by priority, then whichever is worth most; each takes the lines nobody has claimed
 * yet. Lines whose price the cashier changed take no promotion. An item discount is a % or an amount
 * per unit off each qualifying line; buy X get Y discounts the cheapest units of the qualifying
 * lines, {@code get} for every {@code buy + get} units (hourly services don't count).
 * <p>Then the order: the best automatic order promotion whose minimum spend is met, and then the
 * coupon's if it is an order promotion, each on what is left after line promotions and the
 * cashier's item discounts. A line promotion's minimum spend is checked on the cart as priced.
 * <p>Amounts round half-up to cents. The results feed {@link PricingCalculator}.
 */
public final class PromotionEngine {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final TaxKey NO_TAX = new TaxKey(0L, "", BigDecimal.ZERO, false);

    /**
     * A promotion that is running for this sale. {@code couponCodeId}/{@code code} are set when it
     * came from a coupon the customer gave.
     */
    public record Rule(long id, String name, PromotionKind kind, DiscountType type, BigDecimal value, PromotionScope scope,
                       Set<Long> categoryIds, Set<Long> offeringIds, Integer buyQty, Integer getQty, BigDecimal minSpend,
                       BigDecimal maxDiscount, int priority, Long couponCodeId, String code) {

        boolean qualifies(Item item) {
            return switch (scope) {
                case ALL -> true;
                case CATEGORIES -> item.categoryId() != null && categoryIds.contains(item.categoryId());
                case OFFERINGS -> offeringIds.contains(item.offeringId());
            };
        }
    }

    /**
     * A cart line. {@code eligible} is false for a line whose price the cashier changed; {@code
     * discountType}/{@code discountValue} are the cashier's item discount, which comes after the promotion.
     */
    public record Item(Long offeringId, Long categoryId, BigDecimal unitPrice, int quantity, BigDecimal hours, boolean eligible,
                       DiscountType discountType, BigDecimal discountValue) {

        BigDecimal total() {
            return new PricingCalculator.Line(unitPrice, quantity, hours, NO_TAX).gross();
        }
    }

    /** What a line's promotion takes off it; zero and nulls when none. */
    public record LineResult(BigDecimal promoDiscount, Long promotionId, String promotionName) {
    }

    /** A promotion that took something off the sale. */
    public record Applied(long promotionId, Long couponCodeId, String code, String name, PromotionKind kind, BigDecimal amount) {
    }

    /**
     * @param orderDiscount the order promotions together
     * @param couponProblem why the coupon took nothing (null when it applied, or there was none)
     */
    public record Result(List<LineResult> lines, BigDecimal orderDiscount, List<Applied> applied, String couponProblem) {

        public BigDecimal lineDiscount() {
            return lines.stream().map(LineResult::promoDiscount).reduce(zero(), BigDecimal::add);
        }
    }

    private PromotionEngine() {
    }

    /** {@code coupon} is null when no code was given. */
    public static Result apply(List<Item> items, List<Rule> automatic, Rule coupon) {
        BigDecimal[] off = new BigDecimal[items.size()];
        Rule[] by = new Rule[items.size()];
        java.util.Arrays.fill(off, zero());
        BigDecimal cartTotal = items.stream().map(Item::total).reduce(zero(), BigDecimal::add);
        Map<Long, Applied> applied = new LinkedHashMap<>();
        String couponProblem = null;

        // Lines: the coupon's promotion first, then by priority, then whichever is worth most now.
        List<Rule> lineRules = new ArrayList<>(automatic.stream().filter(r -> r.kind().onLines()).toList());
        lineRules.sort(Comparator.comparingInt(Rule::priority).reversed()
                .thenComparing(Comparator.comparing((Rule r) -> worth(r, items, new boolean[items.size()])).reversed())
                .thenComparingLong(Rule::id));
        if (coupon != null && coupon.kind().onLines()) {
            lineRules.addFirst(coupon);
        }
        boolean[] claimed = new boolean[items.size()];
        for (Rule rule : lineRules) {
            if (rule.minSpend() != null && cartTotal.compareTo(rule.minSpend()) < 0) {
                if (rule == coupon) {
                    couponProblem = "Spend at least " + rule.minSpend() + " to use this code";
                }
                continue;
            }
            BigDecimal[] take = lineDiscounts(rule, items, claimed);
            BigDecimal sum = zero();
            for (int i = 0; i < items.size(); i++) {
                if (take[i] == null) {
                    continue;
                }
                // The line took part (a "buy" unit counts too), so no other promotion can use it.
                claimed[i] = true;
                if (take[i].signum() > 0) {
                    off[i] = take[i];
                    by[i] = rule;
                    sum = sum.add(take[i]);
                }
            }
            if (sum.signum() > 0) {
                applied.put(rule.id(), new Applied(rule.id(), rule.couponCodeId(), rule.code(), rule.name(), rule.kind(), sum));
            } else if (rule == coupon && couponProblem == null) {
                couponProblem = "This code doesn't apply to anything in the cart";
            }
        }

        // The order: what is left after line promotions and the cashier's item discounts.
        BigDecimal base = zero();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            var line = new PricingCalculator.Line(it.unitPrice(), it.quantity(), it.hours(), NO_TAX, it.discountType(),
                    it.discountValue(), off[i]);
            base = base.add(line.gross().subtract(line.promo()).subtract(line.itemDiscount()));
        }
        BigDecimal orderDiscount = zero();
        Rule best = null;
        BigDecimal bestAmount = zero();
        for (Rule rule : automatic) {
            if (rule.kind().onLines() || (rule.minSpend() != null && base.compareTo(rule.minSpend()) < 0)) {
                continue;
            }
            BigDecimal amount = orderAmount(rule, base);
            if (amount.compareTo(bestAmount) > 0 || (best != null && amount.compareTo(bestAmount) == 0 && rule.priority() > best.priority())) {
                best = rule;
                bestAmount = amount;
            }
        }
        if (best != null && bestAmount.signum() > 0) {
            orderDiscount = bestAmount;
            applied.put(best.id(), new Applied(best.id(), null, null, best.name(), best.kind(), bestAmount));
        }
        if (coupon != null && !coupon.kind().onLines()) {
            BigDecimal left = base.subtract(orderDiscount);
            if (coupon.minSpend() != null && base.compareTo(coupon.minSpend()) < 0) {
                couponProblem = "Spend at least " + coupon.minSpend() + " to use this code";
            } else {
                BigDecimal amount = orderAmount(coupon, left);
                if (amount.signum() > 0) {
                    orderDiscount = orderDiscount.add(amount);
                    // The same promotion can't run twice: a coupon for the automatic one replaces nothing.
                    applied.merge(coupon.id(), new Applied(coupon.id(), coupon.couponCodeId(), coupon.code(), coupon.name(), coupon.kind(),
                            amount), (a, b) -> new Applied(a.promotionId(), b.couponCodeId(), b.code(), a.name(), a.kind(),
                            a.amount().add(b.amount())));
                } else {
                    couponProblem = "Nothing is left to discount";
                }
            }
        }

        List<LineResult> lines = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            lines.add(by[i] == null ? new LineResult(zero(), null, null) : new LineResult(off[i], by[i].id(), by[i].name()));
        }
        return new Result(lines, orderDiscount, List.copyOf(applied.values()), couponProblem);
    }

    /** What a line promotion would take off all lines nobody has claimed (for ordering promotions by worth). */
    private static BigDecimal worth(Rule rule, List<Item> items, boolean[] claimed) {
        BigDecimal[] take = lineDiscounts(rule, items, claimed);
        BigDecimal sum = zero();
        for (BigDecimal t : take) {
            if (t != null) {
                sum = sum.add(t);
            }
        }
        return sum;
    }

    /**
     * Per line, what the promotion takes off it; null for lines it doesn't use (not qualifying,
     * already claimed, or left out of buy X get Y), zero for lines it uses without discounting.
     */
    private static BigDecimal[] lineDiscounts(Rule rule, List<Item> items, boolean[] claimed) {
        BigDecimal[] take = new BigDecimal[items.size()];
        if (rule.kind() == PromotionKind.ITEM_DISCOUNT) {
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                if (claimed[i] || !it.eligible() || !rule.qualifies(it)) {
                    continue;
                }
                BigDecimal total = it.total();
                BigDecimal d = rule.type() == DiscountType.PERCENT
                        ? Money.round(total.multiply(rule.value()).divide(HUNDRED, 10, RoundingMode.HALF_UP))
                        : Money.round(rule.value().multiply(BigDecimal.valueOf(it.quantity())).min(total));
                take[i] = d.min(total);
            }
            return take;
        }
        // Buy X get Y: the cheapest units of the qualifying lines are discounted, Y for every X + Y.
        List<Integer> qualifying = new ArrayList<>();
        int units = 0;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (!claimed[i] && it.eligible() && it.hours() == null && rule.qualifies(it)) {
                qualifying.add(i);
                units += it.quantity();
            }
        }
        int group = rule.buyQty() + rule.getQty();
        int free = units / group * rule.getQty();
        if (free == 0) {
            return take;
        }
        qualifying.forEach(i -> take[i] = zero());
        qualifying.sort(Comparator.comparing((Integer i) -> items.get(i).unitPrice()).thenComparing(i -> i));
        for (int i : qualifying) {
            if (free == 0) {
                break;
            }
            Item it = items.get(i);
            int n = Math.min(free, it.quantity());
            free -= n;
            BigDecimal d = Money.round(it.unitPrice().multiply(BigDecimal.valueOf(n)).multiply(rule.value())
                    .divide(HUNDRED, 10, RoundingMode.HALF_UP));
            take[i] = d.min(it.total());
        }
        return take;
    }

    private static BigDecimal orderAmount(Rule rule, BigDecimal base) {
        if (base.signum() <= 0) {
            return zero();
        }
        BigDecimal amount = rule.type() == DiscountType.PERCENT
                ? Money.round(base.multiply(rule.value()).divide(HUNDRED, 10, RoundingMode.HALF_UP))
                : Money.round(rule.value());
        if (rule.maxDiscount() != null) {
            amount = amount.min(rule.maxDiscount());
        }
        return amount.min(base);
    }

    private static BigDecimal zero() {
        return Money.round(BigDecimal.ZERO);
    }
}
