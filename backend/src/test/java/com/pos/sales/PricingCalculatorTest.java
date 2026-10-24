package com.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pos.common.ApiException;
import com.pos.sales.PricingCalculator.Line;
import com.pos.sales.PricingCalculator.TaxKey;
import com.pos.sales.PricingCalculator.Totals;

class PricingCalculatorTest {

    private static final TaxKey VAT_ADDED = new TaxKey(1L, "VAT", new BigDecimal("0.10"), false);
    private static final TaxKey VAT_INCLUDED = new TaxKey(1L, "VAT", new BigDecimal("0.10"), true);
    private static final TaxKey EXEMPT = new TaxKey(2L, "Exempt", BigDecimal.ZERO, false);

    private static Line line(String price, int qty, TaxKey tax) {
        return new Line(new BigDecimal(price), qty, null, tax);
    }

    private static void assertTotals(Totals t, String subtotal, String discount, String added, String included, String total) {
        assertThat(t.subtotal()).isEqualByComparingTo(subtotal);
        assertThat(t.discount()).isEqualByComparingTo(discount);
        assertThat(t.taxAdded()).isEqualByComparingTo(added);
        assertThat(t.taxIncluded()).isEqualByComparingTo(included);
        assertThat(t.total()).isEqualByComparingTo(total);
    }

    @Test
    void addsVatOnTopOfExclusivePrices() {
        Totals t = PricingCalculator.calculate(List.of(line("2.50", 2, VAT_ADDED), line("0.50", 3, EXEMPT)), null, null);
        assertTotals(t, "6.50", "0", "0.50", "0", "7.00");
        assertThat(t.taxes()).hasSize(2);
    }

    @Test
    void takesVatOutOfInclusivePrices() {
        // $2.20 including 10% VAT is $2.00 net + $0.20 VAT; the customer pays $2.20.
        Totals t = PricingCalculator.calculate(List.of(line("2.20", 1, VAT_INCLUDED)), null, null);
        assertTotals(t, "2.20", "0", "0", "0.20", "2.20");
        assertThat(t.taxes().getFirst().base()).isEqualByComparingTo("2.00");
    }

    @Test
    void mixesIncludedAndAddedTaxInOneOrder() {
        Totals t = PricingCalculator.calculate(List.of(line("11.00", 1, VAT_INCLUDED), line("10.00", 1, VAT_ADDED)), null, null);
        assertTotals(t, "21.00", "0", "1.00", "1.00", "22.00");
    }

    @Test
    void percentDiscountReducesTheTaxBase() {
        Totals t = PricingCalculator.calculate(List.of(line("10.00", 1, VAT_ADDED)), DiscountType.PERCENT, new BigDecimal("15"));
        assertTotals(t, "10.00", "1.50", "0.85", "0", "9.35");
    }

    @Test
    void discountOnInclusivePricesReducesTheIncludedTax() {
        // $11 incl. VAT, 10% off: $9.90 paid, of which $0.90 is VAT.
        Totals t = PricingCalculator.calculate(List.of(line("11.00", 1, VAT_INCLUDED)), DiscountType.PERCENT, BigDecimal.TEN);
        assertTotals(t, "11.00", "1.10", "0", "0.90", "9.90");
    }

    @Test
    void amountDiscountIsSharedAcrossTaxGroupsAndAddsUp() {
        Totals t = PricingCalculator.calculate(List.of(line("5.00", 1, VAT_ADDED), line("5.00", 1, EXEMPT)),
                DiscountType.AMOUNT, new BigDecimal("2.00"));
        assertTotals(t, "10.00", "2.00", "0.40", "0", "8.40");
        BigDecimal bases = t.taxes().stream().map(PricingCalculator.TaxLine::base).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(bases).isEqualByComparingTo("8.00");
    }

    @Test
    void unevenDiscountSharesStillAddUpToTheDiscount() {
        Totals t = PricingCalculator.calculate(List.of(line("1.00", 1, VAT_ADDED), line("1.00", 1, EXEMPT), line("1.00", 1, VAT_INCLUDED)),
                DiscountType.AMOUNT, new BigDecimal("1.00"));
        BigDecimal net = t.taxes().stream().map(x -> x.base().add(x.included() ? x.tax() : BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net).isEqualByComparingTo("2.00");
    }

    @Test
    void amountDiscountIsCappedAtTheSubtotal() {
        Totals t = PricingCalculator.calculate(List.of(line("3.00", 1, VAT_ADDED)), DiscountType.AMOUNT, new BigDecimal("50"));
        assertTotals(t, "3.00", "3.00", "0", "0", "0");
    }

    @Test
    void rejectsPercentOverOneHundred() {
        assertThatThrownBy(() -> PricingCalculator.calculate(List.of(line("1.00", 1, VAT_ADDED)), DiscountType.PERCENT,
                new BigDecimal("101"))).isInstanceOf(ApiException.class);
    }

    @Test
    void hourlyLinesMultiplyByHoursWorked() {
        Totals t = PricingCalculator.calculate(List.of(new Line(new BigDecimal("15.00"), 1, new BigDecimal("1.5"), VAT_ADDED),
                line("3.00", 1, VAT_ADDED)), null, null);
        assertTotals(t, "25.50", "0", "2.55", "0", "28.05");
    }
}
