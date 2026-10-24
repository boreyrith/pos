package com.pos.config;

import java.util.Set;

import com.pos.catalog.OfferingType;
import com.pos.tax.TaxRate;
import com.pos.tax.TaxRateRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.currency.CurrencyService;
import com.pos.settings.SettingsService;
import com.pos.tax.TaxMode;

/** Settings every signed-in screen needs: pricing, receipts, time zone. */
@RestController
public class StoreController {

    /**
     * {@code currency} is the base currency; see /currencies for the others and their rates.
     * {@code taxScope} lists the entry types that are taxed (under any mode but NONE); the default
     * tax rate is shown under {@code taxLabel}.
     */
    public record StoreSettings(String name, String legalName, String taxId, String address, String phone,
                                String email, String website, String receiptFooter, String currency,
                                TaxMode taxMode, String taxLabel, Set<OfferingType> taxScope, Long defaultTaxRateId, String timezone, boolean khqrSimulated, boolean hasLogo,
                                int logoVersion) {
    }

    private final CurrencyService currencies;
    private final SettingsService settings;
    private final TaxRateRepository rates;

    public StoreController(CurrencyService currencies, SettingsService settings, TaxRateRepository rates) {
        this.rates = rates;
        this.currencies = currencies;
        this.settings = settings;
    }

    @GetMapping("/store")
    public StoreSettings settings() {
        var c = settings.current();
        return new StoreSettings(c.name(), c.legalName(), c.taxId(), c.address(), c.phone(), c.email(), c.website(),
                c.receiptFooter(), currencies.base().code(), c.tax().mode(), c.tax().label(), c.tax().scope(),
                rates.findByDefaultRateTrue().map(TaxRate::getId).orElse(null), c.timezone().getId(),
                settings.khqr().simulated(), c.hasLogo(), c.logoVersion());
    }
}
