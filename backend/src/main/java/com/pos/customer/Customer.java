package com.pos.customer;

import java.time.Instant;

import com.pos.auth.User;

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
 * Someone the business sells to. Exactly one customer is the default "Walk-in Customer", used for
 * sales to anyone not on file; it can't be deactivated.
 */
@Entity
@Table(name = "customers")
@Getter
@Setter
@NoArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 20)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    /** Normalised: digits with an optional leading +. */
    @Column(unique = true, length = 30)
    private String phone;

    @Column(length = 100)
    private String email;

    @Column(length = 255)
    private String address;

    @Column(name = "tax_id", length = 50)
    private String taxId;

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "is_default", nullable = false, updatable = false)
    private boolean defaultCustomer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
