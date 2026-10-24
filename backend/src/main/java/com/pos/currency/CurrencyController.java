package com.pos.currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import com.pos.auth.AuthUser;
import com.pos.auth.UserRepository;
import com.pos.common.ApiException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Currencies and exchange rates. Managers set rates; admins add and edit currencies. */
@RestController
@RequestMapping("/currencies")
public class CurrencyController {

    /** {@code rate} is units of this currency per 1 base unit; 1 for the base. */
    public record CurrencyDto(String code, String name, String symbol, int decimals, BigDecimal cashRounding,
                              boolean base, boolean active, int sortOrder, BigDecimal rate, Instant rateSetAt,
                              String rateSetBy) {
    }

    public record RateDto(Long id, BigDecimal rate, String note, String setBy, Instant setAt) {
        static RateDto from(ExchangeRate r) {
            return new RateDto(r.getId(), r.getRate(), r.getNote(),
                    r.getCreatedBy() == null ? null : r.getCreatedBy().getFullName(), r.getCreatedAt());
        }
    }

    public record RateRequest(@NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000000000")
                              @Digits(integer = 12, fraction = 6) BigDecimal rate,
                              @Size(max = 255) String note) {
    }

    /** For a new currency, {@code rate} is required. */
    public record CurrencyRequest(@NotBlank @Pattern(regexp = "[A-Za-z]{3}") String code,
                                  @NotBlank @Size(max = 50) String name, @NotBlank @Size(max = 8) String symbol,
                                  @NotNull @Min(0) @Max(4) Integer decimals,
                                  @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal cashRounding,
                                  Boolean active, Integer sortOrder,
                                  @DecimalMin(value = "0", inclusive = false) BigDecimal rate) {
    }

    private final CurrencyRepository currencies;
    private final ExchangeRateRepository rates;
    private final UserRepository users;
    private final AuditService audit;

    public CurrencyController(CurrencyRepository currencies, ExchangeRateRepository rates, UserRepository users,
                              AuditService audit) {
        this.audit = audit;
        this.currencies = currencies;
        this.rates = rates;
        this.users = users;
    }

    /** Every currency with its current rate. Open to all staff (the sell screen needs the rates). */
    @GetMapping
    @Transactional(readOnly = true)
    public List<CurrencyDto> list() {
        return currencies.findAllByOrderBySortOrderAscCodeAsc().stream().map(this::toDto).toList();
    }

    @GetMapping("/{code}/rates")
    @Transactional(readOnly = true)
    public List<RateDto> history(@PathVariable String code) {
        return rates.findByCurrencyCodeOrderByCreatedAtDescIdDesc(code.toUpperCase(), PageRequest.of(0, 50)).stream()
                .map(RateDto::from).toList();
    }

    /** Sets a new rate. Orders already made keep the rate they were sold at. */
    @PostMapping("/{code}/rates")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    @PreAuthorize("hasAuthority('CURRENCY_RATES')")
    public CurrencyDto setRate(@PathVariable String code, @Valid @RequestBody RateRequest req,
                               @AuthenticationPrincipal AuthUser user) {
        Currency c = find(code);
        if (c.isBase()) {
            throw ApiException.badRequest(c.getCode() + " is the base currency; its rate is always 1");
        }
        BigDecimal previous = rates.findFirstByCurrencyCodeOrderByCreatedAtDescIdDesc(c.getCode()).map(ExchangeRate::getRate).orElse(null);
        rates.saveAndFlush(new ExchangeRate(c.getCode(), req.rate(), blankToNull(req.note()), users.getReferenceById(user.id())));
        Changes changes = new Changes().add("Rate", previous, req.rate());
        audit.log(AuditAction.EXCHANGE_RATE_SET, "Currency", c.getCode(), c.getCode() + " rate: " + changes.describe().replace("Rate: ", "")
                + (blankToNull(req.note()) == null ? "" : " (" + req.note().trim() + ")"), changes);
        return toDto(c);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    @PreAuthorize("hasAuthority('SETTINGS_CURRENCY')")
    public CurrencyDto create(@Valid @RequestBody CurrencyRequest req, @AuthenticationPrincipal AuthUser user) {
        String code = req.code().toUpperCase();
        if (currencies.existsById(code)) {
            throw ApiException.conflict(code + " already exists");
        }
        if (req.rate() == null) {
            throw ApiException.badRequest("Enter the exchange rate for " + code);
        }
        Currency c = new Currency();
        c.setCode(code);
        apply(c, req);
        currencies.saveAndFlush(c);
        rates.saveAndFlush(new ExchangeRate(code, req.rate(), "Starting rate", users.getReferenceById(user.id())));
        audit.log(AuditAction.CURRENCY_CREATED, "Currency", code, "Added currency " + code + " at " + req.rate().stripTrailingZeros().toPlainString());
        return toDto(c);
    }

    /** Edits a currency's display and cash settings, or turns it on or off. The code can't change. */
    @PutMapping("/{code}")
    @Transactional
    @PreAuthorize("hasAuthority('SETTINGS_CURRENCY')")
    public CurrencyDto update(@PathVariable String code, @Valid @RequestBody CurrencyRequest req) {
        Currency c = find(code);
        if (c.isBase() && Boolean.FALSE.equals(req.active())) {
            throw ApiException.badRequest("The base currency can't be turned off");
        }
        Changes changes = new Changes().add("Name", c.getName(), req.name().trim()).add("Symbol", c.getSymbol(), req.symbol().trim())
                .add("Decimals", c.getDecimals(), req.decimals()).add("Cash step", c.getCashRounding(), req.cashRounding())
                .add("Active", c.isActive() ? "yes" : "no", req.active() == null || req.active() == c.isActive() ? (c.isActive() ? "yes" : "no") : (req.active() ? "yes" : "no"));
        apply(c, req);
        currencies.flush();
        if (!changes.isEmpty()) {
            audit.log(AuditAction.CURRENCY_UPDATED, "Currency", c.getCode(), c.getCode() + ": " + changes.describe(), changes);
        }
        return toDto(c);
    }

    private void apply(Currency c, CurrencyRequest req) {
        c.setName(req.name().trim());
        c.setSymbol(req.symbol().trim());
        c.setDecimals(req.decimals());
        if (req.cashRounding().stripTrailingZeros().scale() > req.decimals()) {
            throw ApiException.badRequest("The cash step can't have more decimals than the currency");
        }
        c.setCashRounding(req.cashRounding());
        if (req.active() != null) {
            c.setActive(req.active());
        }
        if (req.sortOrder() != null) {
            c.setSortOrder(req.sortOrder());
        }
    }

    private Currency find(String code) {
        return currencies.findById(code.toUpperCase()).orElseThrow(() -> ApiException.notFound("Currency"));
    }

    private CurrencyDto toDto(Currency c) {
        var latest = c.isBase() ? null : rates.findFirstByCurrencyCodeOrderByCreatedAtDescIdDesc(c.getCode()).orElse(null);
        return new CurrencyDto(c.getCode(), c.getName(), c.getSymbol(), c.getDecimals(), c.getCashRounding(), c.isBase(),
                c.isActive(), c.getSortOrder(), c.isBase() ? BigDecimal.ONE : latest == null ? null : latest.getRate(),
                latest == null ? null : latest.getCreatedAt(),
                latest == null || latest.getCreatedBy() == null ? null : latest.getCreatedBy().getFullName());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
