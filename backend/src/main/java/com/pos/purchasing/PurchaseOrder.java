package com.pos.purchasing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.pos.auth.User;
import com.pos.inventory.Branch;
import com.pos.inventory.Storage;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "purchase_orders")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "po_number", unique = true, length = 30)
    private String poNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_id")
    private Vendor vendor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    /** Where deliveries go. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "storage_id")
    private Storage storage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PurchaseStatus status = PurchaseStatus.DRAFT;

    @Column(name = "expected_date")
    private LocalDate expectedDate;

    /** The vendor's quote or reference number. */
    @Column(name = "vendor_ref", length = 60)
    private String vendorRef;

    @Column(length = 500)
    private String note;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "ordered_at")
    private Instant orderedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<PurchaseOrderLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<GoodsReceipt> receipts = new ArrayList<>();

    public void addLine(PurchaseOrderLine line) {
        line.setPurchaseOrder(this);
        lines.add(line);
    }

    public void addReceipt(GoodsReceipt r) {
        r.setPurchaseOrder(this);
        receipts.add(r);
    }
}
