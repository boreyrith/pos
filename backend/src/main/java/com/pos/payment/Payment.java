package com.pos.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.pos.currency.CashSettlement;
import com.pos.currency.Money;
import com.pos.sales.Order;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentStatus status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** What a KHQR code charges (USD or KHR), or the base currency for cash. */
    @Column(length = 3)
    private String currency;

    @Column(name = "currency_amount", precision = 18, scale = 4)
    private BigDecimal currencyAmount;

    /** Cash received in all currencies, in base. */
    @Column(precision = 12, scale = 2)
    private BigDecimal tendered;

    /** The change given, in base. */
    @Column(name = "change_due", precision = 12, scale = 2)
    private BigDecimal changeDue;

    @Column(name = "change_currency", length = 3)
    private String changeCurrency;

    /** The change given, in {@code changeCurrency}. */
    @Column(name = "change_amount", precision = 18, scale = 4)
    private BigDecimal changeAmount;

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<PaymentTender> tenders = new ArrayList<>();

    @Column(name = "khqr_payload", length = 512)
    private String khqrPayload;

    @Column(name = "khqr_md5", length = 32)
    private String khqrMd5;

    /** Bakong transaction hash once paid. */
    @Column(name = "external_ref", length = 100)
    private String externalRef;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public static Payment cash(BigDecimal amount, Money base, CashSettlement.Result settlement) {
        Payment p = new Payment();
        p.method = PaymentMethod.CASH;
        p.status = PaymentStatus.PAID;
        p.amount = amount;
        p.currency = base.code();
        p.currencyAmount = amount;
        p.tendered = settlement.tendered();
        p.changeDue = settlement.changeBase();
        p.changeCurrency = settlement.changeCurrency().code();
        p.changeAmount = settlement.changeAmount();
        for (var t : settlement.tenders()) {
            p.tenders.add(new PaymentTender(p, t.currency().code(), t.amount(), t.currency().rate(), t.baseAmount()));
        }
        p.paidAt = Instant.now();
        return p;
    }

    /** {@code amount} in base; {@code currencyAmount} in {@code currency}, as the QR code charges it. */
    public static Payment khqr(BigDecimal amount, String currency, BigDecimal currencyAmount, String payload, String md5,
                               Instant expiresAt) {
        Payment p = new Payment();
        p.method = PaymentMethod.KHQR;
        p.status = PaymentStatus.PENDING;
        p.amount = amount;
        p.currency = currency;
        p.currencyAmount = currencyAmount;
        p.khqrPayload = payload;
        p.khqrMd5 = md5;
        p.expiresAt = expiresAt;
        return p;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    public void markPaid(String externalRef) {
        this.status = PaymentStatus.PAID;
        this.externalRef = externalRef;
        this.paidAt = Instant.now();
    }
}
