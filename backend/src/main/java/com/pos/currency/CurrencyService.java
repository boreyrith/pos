package com.pos.currency;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.ApiException;

/** Currencies with their current rates. */
@Service
public class CurrencyService {

    private final CurrencyRepository currencies;
    private final ExchangeRateRepository rates;

    public CurrencyService(CurrencyRepository currencies, ExchangeRateRepository rates) {
        this.currencies = currencies;
        this.rates = rates;
    }

    /** Active currencies, base first, each with the rate in force now. */
    @Transactional(readOnly = true)
    public Map<String, Money> active() {
        Map<String, Money> result = new LinkedHashMap<>();
        for (Currency c : currencies.findAllByOrderBySortOrderAscCodeAsc()) {
            if (c.isActive() || c.isBase()) {
                result.put(c.getCode(), money(c));
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Money base() {
        return money(currencies.findByBaseTrue().orElseThrow(() -> new IllegalStateException("No base currency")));
    }

    /** An active currency by code, or a clear error. */
    public Money require(Map<String, Money> active, String code) {
        Money m = code == null ? null : active.get(code.trim().toUpperCase());
        if (m == null) {
            throw ApiException.badRequest("Currency " + code + " is not accepted");
        }
        return m;
    }

    Money money(Currency c) {
        BigDecimal rate = c.isBase() ? BigDecimal.ONE
                : rates.findFirstByCurrencyCodeOrderByCreatedAtDescIdDesc(c.getCode()).map(ExchangeRate::getRate)
                        .orElseThrow(() -> ApiException.badRequest("No exchange rate set for " + c.getCode()));
        return new Money(c.getCode(), c.getSymbol(), c.getDecimals(), c.getCashRounding(), rate, c.isBase());
    }
}
