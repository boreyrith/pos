package com.pos.currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pos.common.ApiException;
import com.pos.currency.CashSettlement.Tender;

class CashSettlementTest {

    private static final Money USD = new Money("USD", "$", 2, new BigDecimal("0.01"), BigDecimal.ONE, true);
    private static final Money KHR = new Money("KHR", "៛", 0, new BigDecimal("100"), new BigDecimal("4105"), false);

    private static Tender usd(String amount) {
        return new Tender(USD, new BigDecimal(amount));
    }

    private static Tender khr(String amount) {
        return new Tender(KHR, new BigDecimal(amount));
    }

    @Test
    void mixedCurrenciesWithChangeInRiel() {
        // $10 + 10,000 riel = $12.4361 for a $12.35 bill; $0.0861 change is 353 riel, rounded to 400.
        var r = CashSettlement.settle(new BigDecimal("12.35"), List.of(usd("10"), khr("10000")), KHR);
        assertThat(r.tendered()).isEqualByComparingTo("12.44");
        assertThat(r.changeAmount()).isEqualByComparingTo("400");
        assertThat(r.changeBase()).isEqualByComparingTo("0.10");
        assertThat(r.tenders()).hasSize(2);
    }

    @Test
    void rielRoundedToTheNearestNotePaysTheBill() {
        // $12.35 is 50,696.75 riel; 50,700 is what the customer can actually hand over.
        var r = CashSettlement.settle(new BigDecimal("12.35"), List.of(khr("50700")), KHR);
        assertThat(r.changeAmount()).isEqualByComparingTo("0");
    }

    @Test
    void aShortfallLessThanHalfARielStepCountsAsPaid() {
        // 50,650 riel leaves 46.75 riel unpaid, under half of the 100 riel step.
        var r = CashSettlement.settle(new BigDecimal("12.35"), List.of(khr("50650")), USD);
        assertThat(r.changeAmount()).isEqualByComparingTo("0");
    }

    @Test
    void aRealShortfallIsRejectedWithTheAmountStillDue() {
        assertThatThrownBy(() -> CashSettlement.settle(new BigDecimal("12.35"), List.of(khr("50600")), USD))
                .isInstanceOf(ApiException.class)
                .hasMessage("Still to pay: 100 KHR");
        assertThatThrownBy(() -> CashSettlement.settle(new BigDecimal("12.35"), List.of(usd("12.30")), USD))
                .hasMessage("Still to pay: 0.05 USD");
    }

    @Test
    void dollarsWithDollarChange() {
        var r = CashSettlement.settle(new BigDecimal("7.25"), List.of(usd("20")), USD);
        assertThat(r.changeAmount()).isEqualByComparingTo("12.75");
        assertThat(r.changeBase()).isEqualByComparingTo("12.75");
    }

    @Test
    void rejectsFractionalRiel() {
        assertThatThrownBy(() -> CashSettlement.settle(new BigDecimal("1"), List.of(khr("4100.5")), USD))
                .hasMessageContaining("at most 0 decimals");
    }

    @Test
    void moneyConvertsAndRoundsToCash() {
        assertThat(KHR.fromBase(new BigDecimal("12.35"))).isEqualByComparingTo("50697");
        assertThat(KHR.roundCash(new BigDecimal("50696.75"))).isEqualByComparingTo("50700");
        assertThat(KHR.roundCash(new BigDecimal("50649"))).isEqualByComparingTo("50600");
    }
}
