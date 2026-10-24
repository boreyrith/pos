package com.pos.inventory;

import java.time.Instant;

import com.pos.auth.User;
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

/** One line of stock history. Written by {@link StockService}; read-only elsewhere. */
@Entity
@Table(name = "stock_movements")
@Getter
@NoArgsConstructor
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offering_id")
    private Offering offering;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "storage_id")
    private Storage storage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MovementType type;

    /** Signed change in base units. */
    @Column(nullable = false)
    private int quantity;

    /** The storage's quantity after this change. */
    @Column(nullable = false)
    private int balance;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "transfer_id")
    private Long transferId;

    @Column(name = "adjustment_id")
    private Long adjustmentId;

    /** Read-only view of the same column, for showing the adjustment's number. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "adjustment_id", insertable = false, updatable = false)
    private StockAdjustment adjustment;

    /** The vendor delivery that added this stock, for PURCHASE movements. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "goods_receipt_id", insertable = false, updatable = false)
    private com.pos.purchasing.GoodsReceipt goodsReceipt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AdjustmentReason reason;

    @Column(length = 255)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
