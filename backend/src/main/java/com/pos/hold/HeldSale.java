package com.pos.hold;

import java.math.BigDecimal;
import java.time.Instant;

import com.pos.auth.User;
import com.pos.customer.Customer;
import com.pos.inventory.Branch;
import com.pos.sales.Order;

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

/** A parked sale: the cart as JSON, who held it, who is working on it, and how it ended. */
@Entity
@Table(name = "held_sales")
@Getter
@Setter
@NoArgsConstructor
public class HeldSale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hold_number", unique = true, length = 30)
    private String holdNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(length = 100)
    private String label;

    @Column(length = 255)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private HoldStatus status = HoldStatus.HELD;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "estimated_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal estimatedTotal = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "locked_by")
    private User lockedBy;

    @Column(name = "locked_at")
    private Instant lockedAt;

    /** The order it became (or the unpaid KHQR order while waiting for payment). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by")
    private User closedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "void_reason", length = 255)
    private String voidReason;
}
