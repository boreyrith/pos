package com.pos.sales;

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

/** One tax group of an order: rate, included or added, the net base and the tax. */
@Entity
@Table(name = "order_taxes")
@Getter
@NoArgsConstructor
public class OrderTax {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(name = "tax_rate_id", nullable = false)
    private Long taxRateId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, precision = 6, scale = 4)
    private BigDecimal rate;

    @Column(nullable = false)
    private boolean included;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal base;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal tax;

    OrderTax(Order order, PricingCalculator.TaxLine line) {
        this.order = order;
        this.taxRateId = line.rateId();
        this.name = line.name();
        this.rate = line.rate();
        this.included = line.included();
        this.base = line.base();
        this.tax = line.tax();
    }
}
