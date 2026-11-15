package com.pos.shop;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.pos.auth.User;
import com.pos.branch.Branch;
import com.pos.catalog.Offering;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The online shop's switches (one row). See V40__online_shop.sql. */
@Entity
@Table(name = "shop_settings")
@Getter
@Setter
@NoArgsConstructor
public class ShopSettings {

    public static final short ID = 1;

    @Id
    @JdbcTypeCode(SqlTypes.TINYINT)
    private Short id = ID;

    /** Customers can order; off, the shop shows that it is closed. */
    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "pickup_enabled", nullable = false)
    private boolean pickupEnabled = true;

    @Column(name = "delivery_enabled", nullable = false)
    private boolean deliveryEnabled;

    /** Where delivered orders are packed and sent from. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_branch_id")
    private Branch deliveryBranch;

    /** The "Delivery" service: its price is the delivery fee. Made when delivery is first set up. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_offering_id")
    private Offering deliveryOffering;

    /** Delivery is free when the goods come to at least this (list prices); null: never free. */
    @Column(name = "free_delivery_from", precision = 12, scale = 2)
    private BigDecimal freeDeliveryFrom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
