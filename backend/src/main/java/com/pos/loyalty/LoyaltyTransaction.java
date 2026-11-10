package com.pos.loyalty;

import java.time.Instant;

import com.pos.auth.User;
import com.pos.customer.Customer;

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

/** One change to a customer's points, with the balance after it. */
@Entity
@Table(name = "loyalty_transactions")
@Getter
@NoArgsConstructor
public class LoyaltyTransaction {

    public enum Type { EARN, REDEEM, RESTORE, REVERSE, ADJUST }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Type type;

    /** Signed: earned and given back are positive, used and taken back negative. */
    @Column(nullable = false)
    private int points;

    @Column(name = "balance_after", nullable = false)
    private int balanceAfter;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "return_id")
    private Long returnId;

    @Column(length = 255)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public LoyaltyTransaction(Customer customer, Type type, int points, int balanceAfter, Long orderId, Long returnId, String note,
                              User createdBy) {
        this.customer = customer;
        this.type = type;
        this.points = points;
        this.balanceAfter = balanceAfter;
        this.orderId = orderId;
        this.returnId = returnId;
        this.note = note;
        this.createdBy = createdBy;
    }
}
