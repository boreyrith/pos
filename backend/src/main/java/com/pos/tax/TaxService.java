package com.pos.tax;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import com.pos.catalog.Offering;
import com.pos.sales.PricingCalculator.TaxKey;
import com.pos.settings.SettingsService;

/**
 * The one place that decides how a catalog entry is taxed. Checkout, the catalog (and through it
 * the cart) and reports all go through here, so the mode, scope and rates are applied the same way.
 *
 * <ul>
 * <li>NONE: no tax on anything.</li>
 * <li>INCLUSIVE: the entry's rate, taken out of its listed price.</li>
 * <li>EXCLUSIVE: the entry's rate, added on top of its listed price.</li>
 * </ul>
 * An entry is non-taxable (rate 0) when the mode is NONE, its type is outside the tax scope, or its
 * own tax rate is 0% (e.g. "Exempt"). A package is taxed as one line at its own rate; what is
 * inside it is never taxed again.
 */
@Service
public class TaxService {

    private final SettingsService settings;

    public TaxService(SettingsService settings) {
        this.settings = settings;
    }

    public TaxSettings current() {
        return settings.current().tax();
    }

    /** What lines outside the tax (No VAT mode, or a type not in scope) are labelled. */
    public static final String NOT_TAXED = "Not taxed";

    /** The tax a line of {@code offering} is charged under, given the settings snapshot {@code tax}. */
    public static TaxKey keyFor(Offering offering, TaxSettings tax) {
        TaxRate rate = offering.getTaxRate();
        if (!tax.taxes(offering.getType())) {
            return new TaxKey(rate.getId(), NOT_TAXED, BigDecimal.ZERO, false);
        }
        // The default rate is shown under the store's tax label ("VAT"); other rates under their own
        // name. An exempt (0%) rate keeps its name, so reports show it separately.
        boolean taxed = rate.getRate().signum() > 0;
        String name = rate.isDefaultRate() ? tax.label() : rate.getName();
        return new TaxKey(rate.getId(), name, taxed ? rate.getRate() : BigDecimal.ZERO,
                taxed && tax.mode() == TaxMode.INCLUSIVE);
    }
}
