package com.pos.sales;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.pos.auth.User;
import com.pos.catalog.Offering;
import com.pos.catalog.OfferingType;

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

/**
 * A sold line: a product, an item or a service. Name, SKU, price, tax and (for services)
 * the parts used are copied so later catalog edits don't change history.
 */
@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offering_id")
    private Offering offering;

    @Enumerated(EnumType.STRING)
    @Column(name = "offering_type", nullable = false, length = 10)
    private OfferingType offeringType;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "name_km", length = 150)
    private String nameKm;

    @Column(nullable = false, length = 40)
    private String sku;

    @Column(length = 20)
    private String unit;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    /** The catalog price at the time of sale; differs from {@code unitPrice} when overridden. */
    @Column(name = "list_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal listPrice;

    @Column(name = "price_overridden", nullable = false)
    private boolean priceOverridden;

    @Column(name = "price_override_reason", length = 255)
    private String priceOverrideReason;

    /** Who changed the price (the cashier, who needed the "Override prices" permission). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "price_override_by")
    private User priceOverrideBy;

    @Column(nullable = false)
    private int quantity;

    /** Hours worked, for hourly services only. */
    @Column(precision = 6, scale = 2)
    private BigDecimal hours;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "tax_rate_id", nullable = false)
    private Long taxRateId;

    @Column(name = "tax_name", nullable = false, length = 50)
    private String taxName;

    @Column(name = "tax_rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal taxRate;

    /** Whether {@code lineTotal} includes the tax. */
    @Column(name = "price_includes_tax", nullable = false)
    private boolean priceIncludesTax;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    /** An item discount on this line: % or amount, as entered, and the money it took off. */
    @Enumerated(EnumType.STRING)
    @Column(name = "item_discount_type", length = 10)
    private DiscountType itemDiscountType;

    @Column(name = "item_discount_value", precision = 12, scale = 2)
    private BigDecimal itemDiscountValue;

    @Column(name = "item_discount", nullable = false, precision = 12, scale = 2)
    private BigDecimal itemDiscount = BigDecimal.ZERO;

    /** The line's item discount plus its share of the order discount. */
    @Column(name = "line_discount", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineDiscount = BigDecimal.ZERO;

    /** After discount, without tax. */
    @Column(name = "line_net", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineNet = BigDecimal.ZERO;

    @Column(name = "line_tax", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTax = BigDecimal.ZERO;

    /** What the customer paid for the line: net + tax. Refunds are worked out from this. */
    @Column(name = "line_paid", nullable = false, precision = 12, scale = 2)
    private BigDecimal linePaid = BigDecimal.ZERO;

    /** What the line cost the business at the time of sale (goods, parts and labour); for profit. */
    @Column(name = "cost_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal costTotal = BigDecimal.ZERO;

    @Column(name = "refunded_qty", nullable = false)
    private int refundedQty;

    @Column(name = "refunded_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @Column(name = "refunded_tax", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundedTax = BigDecimal.ZERO;

    /** Who did the work, for services. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id")
    private User staff;

    @Column(name = "staff_name", length = 100)
    private String staffName;

    @OneToMany(mappedBy = "orderItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<OrderItemComponent> components = new ArrayList<>();

    /** For packages: what was inside, for receipts. */
    @OneToMany(mappedBy = "orderItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder")
    private List<OrderItemContent> contents = new ArrayList<>();

    public int returnableQty() {
        return quantity - refundedQty;
    }

    public void apply(PricingCalculator.LineSplit split) {
        itemDiscount = split.itemDiscount();
        lineDiscount = split.discount();
        lineNet = split.net();
        lineTax = split.tax();
        linePaid = split.paid();
    }

    /** Stock this line uses besides its own: a service's parts, or everything stocked inside a package. */
    public void addComponent(Offering item, int quantity) {
        components.add(new OrderItemComponent(this, item, quantity));
    }

    public void addContent(Offering offering, BigDecimal quantity, String unit, int depth) {
        contents.add(new OrderItemContent(this, offering, quantity, unit, depth, contents.size()));
    }
}
