package com.pos.tax;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A named tax rate, e.g. VAT 10% or Exempt 0%. One is the default for new catalog entries. */
@Entity
@Table(name = "tax_rates")
@Getter
@Setter
@NoArgsConstructor
public class TaxRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    /** A fraction: 0.10 for 10%. */
    @Column(nullable = false, precision = 6, scale = 4)
    private BigDecimal rate;

    @Column(name = "is_default", nullable = false)
    private boolean defaultRate;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
