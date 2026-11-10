package com.pos.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.pos.marketing.PromotionEngine.Item;
import com.pos.marketing.PromotionEngine.Result;
import com.pos.marketing.PromotionEngine.Rule;
import com.pos.sales.DiscountType;

class PromotionEngineTest {

    private static final long DIAPERS = 1;
    private static final long TOYS = 2;

    private static Item item(long offeringId, Long categoryId, String price, int qty) {
        return new Item(offeringId, categoryId, new BigDecimal(price), qty, null, true, null, null);
    }

    private static Rule rule(long id, PromotionKind kind, DiscountType type, String value) {
        return new Rule(id, "Promo " + id, kind, type, new BigDecimal(value), PromotionScope.ALL, Set.of(), Set.of(), null, null,
                null, null, 0, null, null);
    }

    private static Rule inCategory(Rule r, long categoryId) {
        return new Rule(r.id(), r.name(), r.kind(), r.type(), r.value(), PromotionScope.CATEGORIES, Set.of(categoryId), Set.of(),
                r.buyQty(), r.getQty(), r.minSpend(), r.maxDiscount(), r.priority(), r.couponCodeId(), r.code());
    }

    private static Rule withPriority(Rule r, int priority) {
        return new Rule(r.id(), r.name(), r.kind(), r.type(), r.value(), r.scope(), r.categoryIds(), r.offeringIds(), r.buyQty(),
                r.getQty(), r.minSpend(), r.maxDiscount(), priority, r.couponCodeId(), r.code());
    }

    private static Rule withMinSpend(Rule r, String minSpend) {
        return new Rule(r.id(), r.name(), r.kind(), r.type(), r.value(), r.scope(), r.categoryIds(), r.offeringIds(), r.buyQty(),
                r.getQty(), new BigDecimal(minSpend), r.maxDiscount(), r.priority(), r.couponCodeId(), r.code());
    }

    private static Rule asCoupon(Rule r, String code) {
        return new Rule(r.id(), r.name(), r.kind(), r.type(), r.value(), r.scope(), r.categoryIds(), r.offeringIds(), r.buyQty(),
                r.getQty(), r.minSpend(), r.maxDiscount(), r.priority(), 99L, code);
    }

    private static Rule buyGet(long id, int buy, int get, String pct) {
        return new Rule(id, "Buy " + buy + " get " + get, PromotionKind.BUY_X_GET_Y, DiscountType.PERCENT, new BigDecimal(pct),
                PromotionScope.ALL, Set.of(), Set.of(), buy, get, null, null, 0, null, null);
    }

    private static String off(Result r, int line) {
        return r.lines().get(line).promoDiscount().toPlainString();
    }

    @Test
    void percentOffACategoryOnlyTouchesItsLines() {
        Result r = PromotionEngine.apply(List.of(item(10, DIAPERS, "12.50", 2), item(11, TOYS, "8.00", 1)),
                List.of(inCategory(rule(1, PromotionKind.ITEM_DISCOUNT, DiscountType.PERCENT, "10"), DIAPERS)), null);
        assertThat(off(r, 0)).isEqualTo("2.50");
        assertThat(off(r, 1)).isEqualTo("0.00");
        assertThat(r.lines().get(0).promotionId()).isEqualTo(1L);
        assertThat(r.applied()).singleElement().satisfies(a -> assertThat(a.amount()).isEqualByComparingTo("2.50"));
    }

    @Test
    void anAmountIsPerUnitAndNeverMoreThanTheLine() {
        Result r = PromotionEngine.apply(List.of(item(10, null, "3.00", 4), item(11, null, "0.80", 2)),
                List.of(rule(1, PromotionKind.ITEM_DISCOUNT, DiscountType.AMOUNT, "1.00")), null);
        assertThat(off(r, 0)).isEqualTo("4.00");
        assertThat(off(r, 1)).isEqualTo("1.60");
    }

    @Test
    void buyTwoGetOneFreeDiscountsTheCheapestUnits() {
        // 3 x 10.00 and 2 x 5.00: five units make one group of three, so one unit is free: a 5.00 one.
        Result r = PromotionEngine.apply(List.of(item(10, null, "10.00", 3), item(11, null, "5.00", 2)),
                List.of(buyGet(1, 2, 1, "100")), null);
        assertThat(off(r, 0)).isEqualTo("0.00");
        assertThat(off(r, 1)).isEqualTo("5.00");
        // Six units would make two groups: two of the cheapest free.
        r = PromotionEngine.apply(List.of(item(10, null, "10.00", 4), item(11, null, "5.00", 2)), List.of(buyGet(1, 2, 1, "100")), null);
        assertThat(off(r, 1)).isEqualTo("10.00");
        // Half off the "get" unit instead of free.
        r = PromotionEngine.apply(List.of(item(10, null, "9.99", 2)), List.of(buyGet(1, 1, 1, "50")), null);
        assertThat(off(r, 0)).isEqualTo("5.00");
    }

    @Test
    void notEnoughUnitsForBuyXGetYLeavesTheLinesForOthers() {
        Result r = PromotionEngine.apply(List.of(item(10, null, "10.00", 2)),
                List.of(withPriority(buyGet(1, 2, 1, "100"), 5), rule(2, PromotionKind.ITEM_DISCOUNT, DiscountType.PERCENT, "10")), null);
        assertThat(off(r, 0)).isEqualTo("2.00");
        assertThat(r.lines().get(0).promotionId()).isEqualTo(2L);
    }

    @Test
    void aLineTakesOnePromotionTheHigherPriorityFirst() {
        Rule small = withPriority(rule(1, PromotionKind.ITEM_DISCOUNT, DiscountType.PERCENT, "5"), 10);
        Rule big = rule(2, PromotionKind.ITEM_DISCOUNT, DiscountType.PERCENT, "20");
        Result r = PromotionEngine.apply(List.of(item(10, null, "10.00", 1)), List.of(big, small), null);
        assertThat(off(r, 0)).isEqualTo("0.50");
        // Same priority: the one worth more wins.
        r = PromotionEngine.apply(List.of(item(10, null, "10.00", 1)), List.of(withPriority(small, 0), big), null);
        assertThat(off(r, 0)).isEqualTo("2.00");
        assertThat(r.applied()).hasSize(1);
    }

    @Test
    void theCouponsLinePromotionGoesFirst() {
        Rule automatic = withPriority(rule(1, PromotionKind.ITEM_DISCOUNT, DiscountType.PERCENT, "30"), 100);
        Rule coupon = asCoupon(rule(2, PromotionKind.ITEM_DISCOUNT, DiscountType.PERCENT, "10"), "TOYS10");
        Result r = PromotionEngine.apply(List.of(item(10, null, "10.00", 1)), List.of(automatic), coupon);
        assertThat(off(r, 0)).isEqualTo("1.00");
        assertThat(r.applied()).singleElement().satisfies(a -> assertThat(a.code()).isEqualTo("TOYS10"));
        assertThat(r.couponProblem()).isNull();
    }

    @Test
    void linesWithAChangedPriceTakeNoPromotion() {
        Item overridden = new Item(10L, null, new BigDecimal("7.00"), 1, null, false, null, null);
        Result r = PromotionEngine.apply(List.of(overridden), List.of(rule(1, PromotionKind.ITEM_DISCOUNT, DiscountType.PERCENT, "10")), null);
        assertThat(off(r, 0)).isEqualTo("0.00");
        assertThat(r.applied()).isEmpty();
    }

    @Test
    void hourlyServicesDontCountForBuyXGetY() {
        Item hourly = new Item(10L, null, new BigDecimal("15.00"), 3, new BigDecimal("2"), true, null, null);
        Result r = PromotionEngine.apply(List.of(hourly), List.of(buyGet(1, 2, 1, "100")), null);
        assertThat(off(r, 0)).isEqualTo("0.00");
    }

    @Test
    void theBestOrderPromotionAppliesOnWhatIsLeft() {
        // 50.00 of goods, 10% off on the line leaves 45.00, and the cashier's 5.00 item discount leaves 40.00.
        Item line = new Item(10L, null, new BigDecimal("50.00"), 1, null, true, DiscountType.AMOUNT, new BigDecimal("5.00"));
        Rule lineTenth = rule(1, PromotionKind.ITEM_DISCOUNT, DiscountType.PERCENT, "10");
        Rule overForty = withMinSpend(rule(2, PromotionKind.ORDER_DISCOUNT, DiscountType.AMOUNT, "3.00"), "40.00");
        Rule overFifty = withMinSpend(rule(3, PromotionKind.ORDER_DISCOUNT, DiscountType.AMOUNT, "8.00"), "50.00");
        Rule capped = new Rule(4, "15% up to 4.50", PromotionKind.ORDER_DISCOUNT, DiscountType.PERCENT, new BigDecimal("15"),
                PromotionScope.ALL, Set.of(), Set.of(), null, null, null, new BigDecimal("4.50"), 0, null, null);
        Result r = PromotionEngine.apply(List.of(line), List.of(lineTenth, overForty, overFifty, capped), null);
        assertThat(off(r, 0)).isEqualTo("5.00");
        // 15% of 40.00 is 6.00, capped at 4.50: the best one that qualifies (over-50 doesn't).
        assertThat(r.orderDiscount()).isEqualByComparingTo("4.50");
        assertThat(r.applied()).extracting(PromotionEngine.Applied::promotionId).containsExactly(1L, 4L);
    }

    @Test
    void anOrderCouponAddsToTheAutomaticOneAndChecksItsMinimum() {
        Rule automatic = rule(1, PromotionKind.ORDER_DISCOUNT, DiscountType.PERCENT, "10");
        Rule coupon = asCoupon(rule(2, PromotionKind.ORDER_DISCOUNT, DiscountType.AMOUNT, "5.00"), "SAVE5");
        Result r = PromotionEngine.apply(List.of(item(10, null, "30.00", 1)), List.of(automatic), coupon);
        assertThat(r.orderDiscount()).isEqualByComparingTo("8.00");
        assertThat(r.couponProblem()).isNull();

        r = PromotionEngine.apply(List.of(item(10, null, "30.00", 1)), List.of(automatic), withMinSpend(coupon, "50.00"));
        assertThat(r.orderDiscount()).isEqualByComparingTo("3.00");
        assertThat(r.couponProblem()).isEqualTo("Spend at least 50.00 to use this code");
    }

    @Test
    void aCouponForNothingInTheCartSaysSo() {
        Rule toysOnly = asCoupon(inCategory(rule(2, PromotionKind.ITEM_DISCOUNT, DiscountType.PERCENT, "10"), TOYS), "TOYS10");
        Result r = PromotionEngine.apply(List.of(item(10, DIAPERS, "12.00", 1)), List.of(), toysOnly);
        assertThat(r.couponProblem()).isEqualTo("This code doesn't apply to anything in the cart");
        assertThat(r.lineDiscount()).isEqualByComparingTo("0");
    }
}
