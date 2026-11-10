package com.pos.tax;

import org.springframework.stereotype.Service;

import com.pos.settings.SettingsService;

/**
 * The store's tax settings. How a catalog entry is taxed under them is decided in one place,
 * {@code catalog.OfferingTaxes}, so checkout, the catalog (and through it the cart) and reports apply
 * the mode, scope and rates the same way.
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
}
