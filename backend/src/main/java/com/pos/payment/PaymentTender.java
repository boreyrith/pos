package com.pos.payment;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Cash handed over in one currency, with the rate used and its value in the base currency. */
@Entity
@Table(name = "payment_tenders")
@Getter
@NoArgsConstructor
public class PaymentTender {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal rate;

    @Column(name = "base_amount", nullable = false, precision = 12, scale = 4)
    private BigDecimal baseAmount;

    PaymentTender(Payment payment, String currency, BigDecimal amount, BigDecimal rate, BigDecimal baseAmount) {
        this.payment = payment;
        this.currency = currency;
        this.amount = amount;
        this.rate = rate;
        this.baseAmount = baseAmount;
    }
}
