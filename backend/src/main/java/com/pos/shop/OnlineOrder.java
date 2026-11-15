package com.pos.shop;

import java.time.Instant;

import com.pos.auth.User;
import com.pos.sales.Order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What an online order adds to its order: pickup or delivery, who to contact, and where it is in
 * preparation. The order itself (lines, totals, payment, stock) is an ordinary sale.
 */
@Entity
@Table(name = "online_orders")
@Getter
@Setter
@NoArgsConstructor
public class OnlineOrder {

    public enum Fulfilment { PICKUP, DELIVERY }

    /** NEW once paid; READY to collect or OUT_FOR_DELIVERY; DONE when the customer has it. */
    public enum Status { NEW, READY, OUT_FOR_DELIVERY, DONE }

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private Fulfilment fulfilment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.NEW;

    /** The account that ordered, or null for a guest. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", updatable = false)
    private CustomerAccount account;

    @Column(name = "contact_name", nullable = false, length = 100)
    private String contactName;

    @Column(name = "contact_phone", nullable = false, length = 30)
    private String contactPhone;

    @Column(length = 255)
    private String address;

    @Column(name = "access_key_hash", nullable = false, length = 64, updatable = false)
    private String accessKeyHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "done_at")
    private Instant doneAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;
}
