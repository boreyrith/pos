package com.pos.purchasing;

import java.math.BigDecimal;

import com.pos.catalog.Offering;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A product or item ordered: {@code quantity} of {@code unit} (factor base units each) at {@code unitCost} per unit. */
@Entity
@Table(name = "purchase_order_lines")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id")
    private PurchaseOrder purchaseOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offering_id")
    private Offering offering;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 40)
    private String sku;

    @Column(name = "unit_id")
    private Long unitId;

    @Column(length = 20)
    private String unit;

    @Column(nullable = false)
    private int factor = 1;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "received_qty", nullable = false)
    private int receivedQty;

    @Column(name = "unit_cost", nullable = false, precision = 12, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    public int remaining() {
        return quantity - receivedQty;
    }
}
