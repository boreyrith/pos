package com.pos.tax;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.Changes;
import com.pos.common.ApiException;
import com.pos.settings.SettingsService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Tax rates. Changing a rate affects sales from then on; orders keep the rate they were sold at.
 * Rates in use can be turned off (hidden when choosing) but not deleted.
 */
@RestController
@RequestMapping("/tax-rates")
public class TaxRateController {

    public record TaxRateDto(Long id, String name, BigDecimal rate, boolean defaultRate, boolean active, int sortOrder,
                             long usage) {
    }

    /** {@code rate} is a fraction: 0.10 for 10%. */
    public record TaxRateRequest(@NotBlank @Size(max = 50) String name,
                                 @NotNull @DecimalMin("0") @DecimalMax("0.9999") @Digits(integer = 1, fraction = 4) BigDecimal rate,
                                 Boolean defaultRate, Boolean active, Integer sortOrder) {
    }

    private final TaxRateRepository rates;
    private final AuditService audit;
    private final SettingsService settings;

    public TaxRateController(TaxRateRepository rates, AuditService audit, SettingsService settings) {
        this.rates = rates;
        this.audit = audit;
        this.settings = settings;
    }

    private static String percent(BigDecimal rate) {
        return rate.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<TaxRateDto> list() {
        return rates.findAllByOrderBySortOrderAscNameAsc().stream().map(this::toDto).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    @PreAuthorize("hasAuthority('SETTINGS_TAX')")
    public TaxRateDto create(@Valid @RequestBody TaxRateRequest req) {
        if (rates.existsByName(req.name().trim())) {
            throw ApiException.conflict("A tax rate called " + req.name().trim() + " already exists");
        }
        TaxRate t = new TaxRate();
        apply(t, req);
        rates.saveAndFlush(t);
        audit.log(AuditAction.TAX_RATE_CREATED, "TaxRate", t.getId(), "Added tax rate " + t.getName() + " " + percent(t.getRate()));
        return toDto(t);
    }

    @PutMapping("/{id}")
    @Transactional
    @PreAuthorize("hasAuthority('SETTINGS_TAX')")
    public TaxRateDto update(@PathVariable Long id, @Valid @RequestBody TaxRateRequest req) {
        TaxRate t = rates.findById(id).orElseThrow(() -> ApiException.notFound("Tax rate"));
        Changes changes = new Changes().add("Name", t.getName(), req.name().trim()).add("Rate", percent(t.getRate()), percent(req.rate()));
        boolean wasDefault = t.isDefaultRate();
        boolean wasActive = t.isActive();
        apply(t, req);
        rates.flush();
        changes.add("Default", wasDefault ? "yes" : "no", t.isDefaultRate() ? "yes" : "no")
                .add("Active", wasActive ? "yes" : "no", t.isActive() ? "yes" : "no");
        if (!changes.isEmpty()) {
            audit.log(AuditAction.TAX_RATE_UPDATED, "TaxRate", t.getId(), "Tax rate " + t.getName() + ": " + changes.describe(), changes);
        }
        return toDto(t);
    }

    private void apply(TaxRate t, TaxRateRequest req) {
        BigDecimal max = settings.current().tax().maxRate();
        if (req.rate().compareTo(max) > 0) {
            throw ApiException.badRequest("Tax rates can be at most " + percent(max) + " (change the maximum under Tax settings)");
        }
        t.setName(req.name().trim());
        t.setRate(req.rate());
        if (req.sortOrder() != null) {
            t.setSortOrder(req.sortOrder());
        }
        if (Boolean.TRUE.equals(req.defaultRate()) && !t.isDefaultRate()) {
            rates.findByDefaultRateTrue().ifPresent(current -> {
                current.setDefaultRate(false);
                rates.flush();
            });
            t.setDefaultRate(true);
        } else if (Boolean.FALSE.equals(req.defaultRate()) && t.isDefaultRate()) {
            throw ApiException.badRequest("Make another rate the default instead");
        }
        if (req.active() != null) {
            if (!req.active() && t.isDefaultRate()) {
                throw ApiException.badRequest("The default rate can't be turned off");
            }
            t.setActive(req.active());
        }
        if (t.isDefaultRate()) {
            t.setActive(true);
        }
    }

    private TaxRateDto toDto(TaxRate t) {
        return new TaxRateDto(t.getId(), t.getName(), t.getRate(), t.isDefaultRate(), t.isActive(), t.getSortOrder(),
                t.getId() == null ? 0 : rates.usageCount(t.getId()));
    }
}
