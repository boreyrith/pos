package com.pos.marketing;

import java.time.Instant;
import java.time.LocalDate;

import com.pos.auth.User;
import com.pos.customer.Customer;

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

/** A code that unlocks a promotion. {@code maxUses} null = any number of times; a code with a customer is theirs alone. */
@Entity
@Table(name = "coupon_codes")
@Getter
@Setter
@NoArgsConstructor
public class CouponCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "promotion_id", updatable = false)
    private Promotion promotion;

    @Column(nullable = false, unique = true, length = 40, updatable = false)
    private String code;

    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @Column(nullable = false)
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", updatable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public boolean usedUp() {
        return maxUses != null && usedCount >= maxUses;
    }
}
