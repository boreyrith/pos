package com.pos.sales;

import java.math.BigDecimal;

import com.pos.catalog.Offering;
import com.pos.catalog.OfferingType;

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

/** Something a sold package contained, as it was at the time of the sale. */
@Entity
@Table(name = "order_item_contents")
@Getter
@NoArgsConstructor
public class OrderItemContent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id")
    private OrderItem orderItem;

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

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(length = 20)
    private String unit;

    @Column(nullable = false)
    private int depth;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    OrderItemContent(OrderItem orderItem, Offering offering, BigDecimal quantity, String unit, int depth, int sortOrder) {
        this.orderItem = orderItem;
        this.offering = offering;
        this.offeringType = offering.getType();
        this.name = offering.getName();
        this.nameKm = offering.getNameKm();
        this.quantity = quantity;
        this.unit = unit;
        this.depth = depth;
        this.sortOrder = sortOrder;
    }
}
