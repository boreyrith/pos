package com.pos.tax;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pos.catalog.Offering;
import com.pos.catalog.OfferingType;
import com.pos.sales.PricingCalculator;
import com.pos.sales.PricingCalculator.TaxKey;

class TaxServiceTest {

    private static final EnumSet<OfferingType> ALL = EnumSet.allOf(OfferingType.class);

    private static TaxRate rate(long id, String name, String r, boolean isDefault) {
        TaxRate t = new TaxRate();
        t.setId(id);
        t.setName(name);
        t.setRate(new BigDecimal(r));
        t.setDefaultRate(isDefault);
        return t;
    }

    private static final TaxRate VAT = rate(1, "Standard", "0.1000", true);
    private static final TaxRate EXEMPT = rate(2, "Exempt", "0.0000", false);
    private static final TaxRate SPECIFIC = rate(3, "Specific tax", "0.0500", false);

    private static Offering offering(OfferingType type, TaxRate rate) {
        Offering o = new Offering(type);
        o.setTaxRate(rate);
        return o;
    }

    private static TaxSettings settings(TaxMode mode, EnumSet<OfferingType> scope) {
        return new TaxSettings(mode, "VAT", new BigDecimal("0.30"), scope);
    }

    @Test
    void noVatChargesNothing() {
        TaxKey k = TaxService.keyFor(offering(OfferingType.PRODUCT, VAT), settings(TaxMode.NONE, ALL));
        assertThat(k.rate()).isZero();
        assertThat(k.included()).isFalse();
        assertThat(k.name()).isEqualTo(TaxService.NOT_TAXED);
    }

    @Test
    void vatIncludedTakesTaxOutOfThePrice() {
        TaxKey k = TaxService.keyFor(offering(OfferingType.PRODUCT, VAT), settings(TaxMode.INCLUSIVE, ALL));
        assertThat(k.rate()).isEqualByComparingTo("0.10");
        assertThat(k.included()).isTrue();
        // The default rate is shown under the store's label.
        assertThat(k.name()).isEqualTo("VAT");
    }

    @Test
    void vatSetupValueAddsTaxOnTop() {
        TaxKey k = TaxService.keyFor(offering(OfferingType.SERVICE, VAT), settings(TaxMode.EXCLUSIVE, ALL));
        assertThat(k.rate()).isEqualByComparingTo("0.10");
        assertThat(k.included()).isFalse();
    }

    @Test
    void otherRatesKeepTheirName() {
        TaxKey k = TaxService.keyFor(offering(OfferingType.PRODUCT, SPECIFIC), settings(TaxMode.EXCLUSIVE, ALL));
        assertThat(k.name()).isEqualTo("Specific tax");
        assertThat(k.rate()).isEqualByComparingTo("0.05");
    }

    @Test
    void exemptRateIsNeverTaxedInAnyMode() {
        for (TaxMode mode : TaxMode.values()) {
            TaxKey k = TaxService.keyFor(offering(OfferingType.PRODUCT, EXEMPT), settings(mode, ALL));
            assertThat(k.rate()).isZero();
            assertThat(k.included()).isFalse();
        }
        assertThat(TaxService.keyFor(offering(OfferingType.PRODUCT, EXEMPT), settings(TaxMode.EXCLUSIVE, ALL)).name()).isEqualTo("Exempt");
    }

    @Test
    void typesOutsideTheScopeAreNotTaxed() {
        var scope = EnumSet.of(OfferingType.PRODUCT, OfferingType.PACKAGE);
        TaxKey service = TaxService.keyFor(offering(OfferingType.SERVICE, VAT), settings(TaxMode.EXCLUSIVE, scope));
        TaxKey item = TaxService.keyFor(offering(OfferingType.ITEM, VAT), settings(TaxMode.INCLUSIVE, scope));
        TaxKey product = TaxService.keyFor(offering(OfferingType.PRODUCT, VAT), settings(TaxMode.EXCLUSIVE, scope));
        assertThat(service.rate()).isZero();
        assertThat(item.rate()).isZero();
        assertThat(item.included()).isFalse();
        assertThat(product.rate()).isEqualByComparingTo("0.10");
    }

    @Test
    void packageIsTaxedOnceAtItsOwnPrice() {
        // A package sells as one line; its contents are never priced or taxed again.
        Offering pkg = offering(OfferingType.PACKAGE, VAT);
        TaxKey k = TaxService.keyFor(pkg, settings(TaxMode.EXCLUSIVE, ALL));
        var totals = PricingCalculator.calculate(List.of(new PricingCalculator.Line(new BigDecimal("25.00"), 2, null, k)), null, null);
        assertThat(totals.taxes()).hasSize(1);
        assertThat(totals.tax()).isEqualByComparingTo("5.00");
        assertThat(totals.total()).isEqualByComparingTo("55.00");

        TaxKey off = TaxService.keyFor(pkg, settings(TaxMode.EXCLUSIVE, EnumSet.of(OfferingType.PRODUCT)));
        assertThat(off.rate()).isZero();
    }

    @Test
    void outOfScopeAndTaxedLinesOfTheSameRateAreSeparateGroups() {
        TaxSettings s = settings(TaxMode.EXCLUSIVE, EnumSet.of(OfferingType.SERVICE));
        var totals = PricingCalculator.calculate(List.of(
                new PricingCalculator.Line(new BigDecimal("5.00"), 2, null, TaxService.keyFor(offering(OfferingType.PRODUCT, VAT), s)),
                new PricingCalculator.Line(new BigDecimal("20.00"), 1, null, TaxService.keyFor(offering(OfferingType.SERVICE, VAT), s))),
                null, null);
        assertThat(totals.taxes()).hasSize(2);
        assertThat(totals.tax()).isEqualByComparingTo("2.00");
        assertThat(totals.taxableAmount()).isEqualByComparingTo("20.00");
        assertThat(totals.nonTaxableAmount()).isEqualByComparingTo("10.00");
    }
}
