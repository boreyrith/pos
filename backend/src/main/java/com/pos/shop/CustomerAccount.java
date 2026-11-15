package com.pos.shop;

import java.time.Instant;

import com.pos.auth.LoginAttempts;
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

/**
 * A customer's online-shop account: their phone and a password, on the customer record with that
 * phone. Phones aren't verified (no SMS provider yet), so an account only sees its own online orders.
 */
@Entity
@Table(name = "customer_accounts")
@Getter
@Setter
@NoArgsConstructor
public class CustomerAccount {

    /** Wrong passwords in a row before the account locks, and for how long: the same as for staff. */
    static final int MAX_FAILURES = LoginAttempts.MAX_FAILURES;
    static final int LOCK_MINUTES = (int) LoginAttempts.LOCK_FOR.toMinutes();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    @Column(nullable = false, length = 30)
    private String phone;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    /**
     * Its customer record is theirs: made by this account, or checked by staff at the counter. Until then
     * orders don't earn the record's points or get its member offers (phones aren't verified yet).
     */
    @Column(nullable = false)
    private boolean verified;

    @Column(name = "failed_logins", nullable = false)
    private int failedLogins;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    CustomerAccount(Customer customer, String phone, String passwordHash, boolean verified) {
        this.customer = customer;
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.verified = verified;
    }

    /** The customer whose points and member offers this account's orders get, or null until confirmed. */
    Long customerIdForOrders() {
        return verified ? customer.getId() : null;
    }

    boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
