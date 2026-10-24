package com.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.pos.sales.RefundSplit.Share;

class RefundSplitTest {

    private static final BigDecimal Z = new BigDecimal("0.00");

    @Test
    void fullReturnRefundsExactlyWhatWasPaid() {
        Share s = RefundSplit.share(new BigDecimal("4.46"), new BigDecimal("0.41"), 3, 0, Z, Z, 3);
        assertThat(s.amount()).isEqualByComparingTo("4.46");
        assertThat(s.tax()).isEqualByComparingTo("0.41");
    }

    @Test
    void partialReturnRefundsItsShareWithTax() {
        Share s = RefundSplit.share(new BigDecimal("4.46"), new BigDecimal("0.41"), 3, 0, Z, Z, 1);
        assertThat(s.amount()).isEqualByComparingTo("1.49");
        assertThat(s.tax()).isEqualByComparingTo("0.14");
    }

    @Test
    void returnsOneByOneAddUpToTheLine() {
        BigDecimal paid = new BigDecimal("10.00");
        BigDecimal tax = new BigDecimal("0.91");
        BigDecimal refunded = Z;
        BigDecimal taxRefunded = Z;
        for (int done = 0; done < 3; done++) {
            Share s = RefundSplit.share(paid, tax, 3, done, refunded, taxRefunded, 1);
            refunded = refunded.add(s.amount());
            taxRefunded = taxRefunded.add(s.tax());
        }
        assertThat(refunded).isEqualByComparingTo(paid);
        assertThat(taxRefunded).isEqualByComparingTo(tax);
    }

    @Test
    void splittingReturnsDifferentlyRefundsTheSame() {
        BigDecimal paid = new BigDecimal("7.01");
        BigDecimal tax = new BigDecimal("0.64");
        Share twoAtOnce = RefundSplit.share(paid, tax, 7, 0, Z, Z, 2);
        Share first = RefundSplit.share(paid, tax, 7, 0, Z, Z, 1);
        Share second = RefundSplit.share(paid, tax, 7, 1, first.amount(), first.tax(), 1);
        assertThat(first.amount().add(second.amount())).isEqualByComparingTo(twoAtOnce.amount());
        assertThat(first.tax().add(second.tax())).isEqualByComparingTo(twoAtOnce.tax());
    }

    @Test
    void untaxedLineRefundsNoTax() {
        Share s = RefundSplit.share(new BigDecimal("9.00"), Z, 2, 0, Z, Z, 1);
        assertThat(s.amount()).isEqualByComparingTo("4.50");
        assertThat(s.tax()).isZero();
    }

    @Test
    void cannotReturnMoreThanIsLeft() {
        assertThatThrownBy(() -> RefundSplit.share(new BigDecimal("3.00"), Z, 3, 2, new BigDecimal("2.00"), Z, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RefundSplit.share(new BigDecimal("3.00"), Z, 3, 0, Z, Z, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
