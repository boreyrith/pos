package com.pos.purchasing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.pos.auth.User;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "vendor_payments")
@Getter
@Setter
@NoArgsConstructor
public class VendorPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_invoice_id")
    private VendorInvoice invoice;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VendorPaymentMethod method;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    /** Bank or cheque reference. */
    @Column(length = 100)
    private String reference;

    @Column(length = 255)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
