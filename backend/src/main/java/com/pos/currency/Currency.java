package com.pos.currency;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "currencies")
@Getter
@Setter
@NoArgsConstructor
public class Currency {

    /** ISO 4217 code, e.g. USD, KHR. */
    @Id
    @Column(length = 3)
    private String code;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 8)
    private String symbol;

    @Column(nullable = false)
    private int decimals;

    /** Smallest amount handed over in cash, e.g. 100 for riel. */
    @Column(name = "cash_rounding", nullable = false, precision = 12, scale = 4)
    private BigDecimal cashRounding;

    /** Prices, totals and reports are in the base currency. There is exactly one. */
    @Column(nullable = false, updatable = false)
    private boolean base;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
