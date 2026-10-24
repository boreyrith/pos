package com.pos.settings;

import java.time.ZoneId;
import java.util.EnumSet;

import com.pos.catalog.OfferingType;
import com.pos.tax.TaxSettings;

/** A snapshot of the company settings, cached by {@link SettingsService}. */
public record Company(String name, String legalName, String taxId, String address, String phone, String email,
                      String website, String receiptFooter, TaxSettings tax, ZoneId timezone,
                      String khqrAccountId, String khqrMerchantName, String khqrMerchantCity, boolean hasLogo,
                      int logoVersion) {

    static Company from(CompanySettings s) {
        EnumSet<OfferingType> scope = EnumSet.noneOf(OfferingType.class);
        if (s.isTaxProducts()) {
            scope.add(OfferingType.PRODUCT);
        }
        if (s.isTaxServices()) {
            scope.add(OfferingType.SERVICE);
        }
        if (s.isTaxItems()) {
            scope.add(OfferingType.ITEM);
        }
        if (s.isTaxPackages()) {
            scope.add(OfferingType.PACKAGE);
        }
        return new Company(s.getName(), s.getLegalName(), s.getTaxId(), s.getAddress(), s.getPhone(), s.getEmail(),
                s.getWebsite(), s.getReceiptFooter(), new TaxSettings(s.getTaxMode(), s.getTaxLabel(), s.getTaxMaxRate(), scope),
                ZoneId.of(s.getTimezone()), s.getKhqrAccountId(), s.getKhqrMerchantName(), s.getKhqrMerchantCity(),
                s.getLogoType() != null, s.getLogoVersion());
    }
}
