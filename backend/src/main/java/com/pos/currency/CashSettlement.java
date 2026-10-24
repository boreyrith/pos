package com.pos.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.pos.common.ApiException;

/**
 * Works out a cash payment made in one or more currencies. A shortfall smaller than half the cash
 * step of a currency the customer paid in counts as paid: $12.35 at 4,105 is 50,696.75 riel, so
 * 50,700 riel pays it (no riel coin covers the last 0.25). Change is given in the chosen
 * currency, rounded to its cash step.
 */
public final class CashSettlement {

    public record Tender(Money currency, BigDecimal amount) {
    }

    public record TenderLine(Money currency, BigDecimal amount, BigDecimal baseAmount) {
    }

    /**
     * @param tendered     total received, in base, 2 decimals
     * @param changeAmount change handed over, in {@code changeCurrency}
     * @param changeBase   that change in base, 2 decimals
     */
    public record Result(List<TenderLine> tenders, BigDecimal tendered, Money changeCurrency, BigDecimal changeAmount,
                         BigDecimal changeBase) {
    }

    private CashSettlement() {
    }

    public static Result settle(BigDecimal totalBase, List<Tender> tenders, Money changeCurrency) {
        List<TenderLine> lines = new ArrayList<>();
        BigDecimal received = BigDecimal.ZERO;
        for (Tender t : tenders) {
            if (t.amount().signum() < 0) {
                throw ApiException.badRequest("Amounts received can't be negative");
            }
            if (t.amount().signum() == 0) {
                continue;
            }
            if (t.currency().hasTooManyDecimals(t.amount())) {
                throw ApiException.badRequest(t.currency().code() + " amounts can have at most "
                        + t.currency().decimals() + " decimals");
            }
            BigDecimal base = t.currency().toBase(t.amount());
            lines.add(new TenderLine(t.currency(), t.amount(), base));
            received = received.add(base);
        }

        BigDecimal remaining = totalBase.subtract(received);
        if (remaining.signum() > 0) {
            boolean roundsAway = lines.stream().anyMatch(l -> isLessThanHalfStep(l.currency(), remaining));
            if (!roundsAway) {
                List<Money> shown = lines.isEmpty() ? List.of(changeCurrency)
                        : lines.stream().map(TenderLine::currency).distinct().toList();
                throw ApiException.badRequest("Still to pay: " + describe(remaining, shown));
            }
        }

        BigDecimal changeAmount = BigDecimal.ZERO.setScale(changeCurrency.decimals());
        BigDecimal changeBase = BigDecimal.ZERO.setScale(2);
        if (remaining.signum() < 0) {
            changeAmount = changeCurrency.roundCash(remaining.negate().multiply(changeCurrency.rate()));
            changeBase = changeCurrency.toBase(changeAmount).setScale(2, RoundingMode.HALF_UP);
        }
        return new Result(lines, received.setScale(2, RoundingMode.HALF_UP), changeCurrency, changeAmount, changeBase);
    }

    private static boolean isLessThanHalfStep(Money currency, BigDecimal remainingBase) {
        BigDecimal inCurrency = remainingBase.multiply(currency.rate());
        return inCurrency.multiply(BigDecimal.TWO).compareTo(currency.cashRounding()) < 0;
    }

    /** "1.20 USD or 4,900 KHR": the shortfall in each currency, rounded up to a cash step. */
    private static String describe(BigDecimal remainingBase, List<Money> currencies) {
        List<String> parts = new ArrayList<>();
        for (Money c : currencies) {
            BigDecimal steps = remainingBase.multiply(c.rate()).divide(c.cashRounding(), 0, RoundingMode.UP);
            parts.add(steps.multiply(c.cashRounding()).setScale(c.decimals(), RoundingMode.HALF_UP).toPlainString()
                    + " " + c.code());
        }
        return String.join(" or ", parts);
    }
}
