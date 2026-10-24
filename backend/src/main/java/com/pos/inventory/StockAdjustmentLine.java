package com.pos.inventory;

import java.math.BigDecimal;

import com.pos.catalog.Offering;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** One entry's change in an adjustment. Quantities are in base units. */
@Entity
@Table(name = "stock_adjustment_lines")
@Getter
@Setter
@NoArgsConstructor
public class StockAdjustmentLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "adjustment_id")
    private StockAdjustment adjustment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offering_id")
    private Offering offering;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 40)
    private String sku;

    @Column(length = 20)
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AdjustmentReason reason;

    @Column(length = 255)
    private String note;

    @Column(name = "before_qty", nullable = false)
    private int beforeQty;

    @Column(name = "change_qty", nullable = false)
    private int changeQty;

    @Column(name = "after_qty", nullable = false)
    private int afterQty;

    /** Counts only: what the count sheet showed when loaded; differs from beforeQty if stock moved meanwhile. */
    @Column(name = "expected_qty")
    private Integer expectedQty;

    @Column(name = "unit_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "value_change", nullable = false, precision = 14, scale = 2)
    private BigDecimal valueChange;
}
