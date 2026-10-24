package com.pos.purchasing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.pos.auth.User;
import com.pos.inventory.Storage;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One delivery received against a purchase order; it added the stock in its lines. */
@Entity
@Table(name = "goods_receipts")
@Getter
@Setter
@NoArgsConstructor
public class GoodsReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receipt_number", unique = true, length = 30)
    private String receiptNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id")
    private PurchaseOrder purchaseOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "storage_id")
    private Storage storage;

    /** The vendor's delivery note number. */
    @Column(name = "delivery_ref", length = 60)
    private String deliveryRef;

    @Column(length = 255)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "received_by")
    private User receivedBy;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    @OneToMany(mappedBy = "goodsReceipt", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<GoodsReceiptLine> lines = new ArrayList<>();
}
