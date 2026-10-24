package com.pos.tax;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.Changes;
import com.pos.auth.AuthUser;
import com.pos.auth.UserRepository;
import com.pos.common.ApiException;
import com.pos.settings.CompanySettings;
import com.pos.settings.CompanySettingsRepository;
import com.pos.settings.SettingsService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Settings > Taxes: the tax mode and the VAT rate, label, maximum rate and scope. Managers can see
 * it; admins change it. Changes apply to sales from then on; past orders keep their own snapshot.
 * The VAT rate is the default tax rate's rate (other named rates are managed at /tax-rates).
 */
@RestController
@RequestMapping("/settings/tax")
public class TaxSettingsController {

    /** Rates are fractions: 0.10 for 10%. */
    public record TaxSettingsDto(TaxMode mode, String label, BigDecimal vatRate, Long defaultRateId, String defaultRateName,
                                 BigDecimal maxRate, boolean taxProducts, boolean taxServices, boolean taxItems,
                                 boolean taxPackages, String taxId, String updatedBy, Instant updatedAt) {
    }

    public record TaxSettingsRequest(
            @NotNull TaxMode mode,
            @NotBlank @Size(max = 30) String label,
            @NotNull @DecimalMin("0") @DecimalMax("0.99") @Digits(integer = 1, fraction = 4) BigDecimal vatRate,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("0.99") @Digits(integer = 1, fraction = 4) BigDecimal maxRate,
            @NotNull Boolean taxProducts, @NotNull Boolean taxServices, @NotNull Boolean taxItems, @NotNull Boolean taxPackages,
            @Size(max = 50) String taxId) {
    }

    private final CompanySettingsRepository companies;
    private final TaxRateRepository rates;
    private final SettingsService settings;
    private final UserRepository users;
    private final AuditService audit;

    public TaxSettingsController(CompanySettingsRepository companies, TaxRateRepository rates, SettingsService settings,
                                 UserRepository users, AuditService audit) {
        this.companies = companies;
        this.rates = rates;
        this.settings = settings;
        this.users = users;
        this.audit = audit;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyAuthority('SETTINGS_VIEW','REPORTS_TAX')")
    public TaxSettingsDto get() {
        settings.current();
        return toDto(companies.findById(CompanySettings.ID).orElseThrow());
    }

    @PutMapping
    @Transactional
    @PreAuthorize("hasAuthority('SETTINGS_TAX')")
    public TaxSettingsDto update(@Valid @RequestBody TaxSettingsRequest req, @AuthenticationPrincipal AuthUser user) {
        settings.current();
        CompanySettings s = companies.findById(CompanySettings.ID).orElseThrow();
        TaxRate vat = rates.findByDefaultRateTrue().orElseThrow(() -> ApiException.badRequest("Set up a default tax rate first"));
        if (req.vatRate().compareTo(req.maxRate()) > 0) {
            throw ApiException.badRequest("The VAT rate can't be higher than the maximum rate (" + percent(req.maxRate()) + ")");
        }
        rates.findAll().stream().filter(r -> !r.getId().equals(vat.getId()) && r.getRate().compareTo(req.maxRate()) > 0).findFirst()
                .ifPresent(r -> {
                    throw ApiException.badRequest("The tax rate " + r.getName() + " (" + percent(r.getRate())
                            + ") is above that maximum. Lower it first.");
                });

        Changes changes = new Changes()
                .add("Tax mode", label(s.getTaxMode()), label(req.mode()))
                .add("VAT rate", percent(vat.getRate()), percent(req.vatRate()))
                .add("Tax label", s.getTaxLabel(), req.label().trim())
                .add("Maximum rate", percent(s.getTaxMaxRate()), percent(req.maxRate()))
                .add("Taxes products", yes(s.isTaxProducts()), yes(req.taxProducts()))
                .add("Taxes services", yes(s.isTaxServices()), yes(req.taxServices()))
                .add("Taxes items", yes(s.isTaxItems()), yes(req.taxItems()))
                .add("Taxes packages", yes(s.isTaxPackages()), yes(req.taxPackages()))
                .add("Tax ID", s.getTaxId(), blankToNull(req.taxId()));

        s.setTaxMode(req.mode());
        s.setTaxLabel(req.label().trim());
        s.setTaxMaxRate(req.maxRate());
        s.setTaxProducts(req.taxProducts());
        s.setTaxServices(req.taxServices());
        s.setTaxItems(req.taxItems());
        s.setTaxPackages(req.taxPackages());
        s.setTaxId(blankToNull(req.taxId()));
        s.setUpdatedBy(users.getReferenceById(user.id()));
        s.setUpdatedAt(Instant.now());
        vat.setRate(req.vatRate());
        rates.flush();
        settings.refresh(companies.saveAndFlush(s));
        if (!changes.isEmpty()) {
            audit.log(AuditAction.SETTINGS_UPDATED, "Settings", "tax", "Tax settings: " + changes.describe(), changes);
        }
        return toDto(s);
    }

    private TaxSettingsDto toDto(CompanySettings s) {
        TaxRate vat = rates.findByDefaultRateTrue().orElse(null);
        return new TaxSettingsDto(s.getTaxMode(), s.getTaxLabel(), vat == null ? BigDecimal.ZERO : vat.getRate(),
                vat == null ? null : vat.getId(), vat == null ? null : vat.getName(), s.getTaxMaxRate(), s.isTaxProducts(),
                s.isTaxServices(), s.isTaxItems(), s.isTaxPackages(), s.getTaxId(),
                s.getUpdatedBy() == null ? null : s.getUpdatedBy().getFullName(), s.getUpdatedAt());
    }

    static String label(TaxMode mode) {
        return switch (mode) {
            case NONE -> "No VAT";
            case INCLUSIVE -> "VAT included";
            case EXCLUSIVE -> "VAT setup value";
        };
    }

    private static String percent(BigDecimal rate) {
        return rate.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }

    private static String yes(boolean b) {
        return b ? "yes" : "no";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
