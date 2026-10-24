package com.pos.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.pos.auth.User;
import com.pos.tax.TaxMode;
import com.pos.inventory.Branch;
import com.pos.inventory.Storage;
import com.pos.payment.Payment;

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
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", unique = true, length = 30)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cashier_id")
    private User cashier;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    /** The storage stock was taken from, and is returned to on cancel or refund. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "storage_id")
    private Storage storage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", length = 10)
    private DiscountType discountType;

    @Column(name = "discount_value", precision = 12, scale = 2)
    private BigDecimal discountValue;

    /** The order discount (on the subtotal after item discounts). */
    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount;

    /** Item discounts on all lines. */
    @Column(name = "item_discount", nullable = false, precision = 12, scale = 2)
    private BigDecimal itemDiscount = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal taxAmount;

    /** The part of {@code taxAmount} already inside the listed prices. */
    @Column(name = "tax_included", nullable = false, precision = 12, scale = 2)
    private BigDecimal taxIncluded = BigDecimal.ZERO;

    /** The tax mode and label at the time of sale; later setting changes don't touch them. */
    @Enumerated(EnumType.STRING)
    @Column(name = "tax_mode", nullable = false, length = 12)
    private TaxMode taxMode;

    @Column(name = "tax_label", nullable = false, length = 30)
    private String taxLabel;

    /** Net amounts (after discount, before tax) of lines that were and weren't charged tax. */
    @Column(name = "taxable_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal taxableAmount = BigDecimal.ZERO;

    @Column(name = "non_taxable_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal nonTaxableAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    /** Refunded so far by returns, and the tax part of it. */
    @Column(name = "refunded_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundedTotal = BigDecimal.ZERO;

    @Column(name = "refunded_tax", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundedTax = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 255)
    private String note;

    /** Who it was for; the Walk-in Customer when nobody was picked. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private com.pos.customer.Customer customer;

    /** The customer's name at the time of sale, or a name typed for a walk-in; optional. */
    @Column(name = "customer_name", length = 100)
    private String customerName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<OrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<Payment> payments = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderRate> rates = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<OrderTax> taxes = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<OrderReturn> returns = new ArrayList<>();

    public void addReturn(OrderReturn ret) {
        ret.setOrder(this);
        returns.add(ret);
    }

    public void addTax(PricingCalculator.TaxLine line) {
        taxes.add(new OrderTax(this, line));
    }

    public void addRate(String currency, BigDecimal rate) {
        rates.add(new OrderRate(this, currency, rate));
    }

    public void addItem(OrderItem item) {
        item.setOrder(this);
        items.add(item);
    }

    public void addPayment(Payment payment) {
        payment.setOrder(this);
        payments.add(payment);
    }

    public void complete() {
        status = OrderStatus.COMPLETED;
        completedAt = Instant.now();
    }
}
