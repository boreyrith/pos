package com.pos.catalog;

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
import lombok.Setter;

/**
 * One line of a package: {@code quantity} of {@code component} in {@code unit}. The unit is a
 * base or pack unit for products and items, and null for services (jobs, or hours if hourly) and
 * nested packages.
 */
@Entity
@Table(name = "package_lines")
@Getter
@Setter
@NoArgsConstructor
public class PackageLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "package_id")
    private Offering pkg;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id")
    private Offering component;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id")
    private Unit unit;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public PackageLine(Offering pkg, Offering component, BigDecimal quantity, Unit unit, int sortOrder) {
        this.pkg = pkg;
        this.component = component;
        this.quantity = quantity;
        this.unit = unit;
        this.sortOrder = sortOrder;
    }
}
