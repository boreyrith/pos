package com.pos.purchasing;

import java.math.BigDecimal;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "goods_receipt_lines")
@Getter
@Setter
@NoArgsConstructor
public class GoodsReceiptLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "goods_receipt_id")
    private GoodsReceipt goodsReceipt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "po_line_id")
    private PurchaseOrderLine line;

    /** In the order line's unit. */
    @Column(nullable = false)
    private int quantity;

    /** As added to stock. */
    @Column(name = "base_quantity", nullable = false)
    private int baseQuantity;

    @Column(name = "unit_cost", nullable = false, precision = 12, scale = 4)
    private BigDecimal unitCost;
}
