package com.pos.currency;

import java.math.BigDecimal;
import java.time.Instant;

import com.pos.auth.User;

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

/** A rate as set at one moment: {@code rate} units of the currency per 1 base unit. */
@Entity
@Table(name = "exchange_rates")
@Getter
@NoArgsConstructor
public class ExchangeRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal rate;

    @Column(length = 255)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ExchangeRate(String currencyCode, BigDecimal rate, String note, User createdBy) {
        this.currencyCode = currencyCode;
        this.rate = rate;
        this.note = note;
        this.createdBy = createdBy;
    }
}
