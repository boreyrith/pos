package com.pos.sales;

import java.math.BigDecimal;

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

/** What one promotion took off a sale, kept as it was then (name, code, kind, amount). */
@Entity
@Table(name = "order_promotions")
@Getter
@NoArgsConstructor
public class OrderPromotion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(name = "promotion_id", nullable = false)
    private Long promotionId;

    @Column(name = "coupon_code_id")
    private Long couponCodeId;

    @Column(length = 40)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    public OrderPromotion(Order order, Long promotionId, Long couponCodeId, String code, String name, String kind, BigDecimal amount) {
        this.order = order;
        this.promotionId = promotionId;
        this.couponCodeId = couponCodeId;
        this.code = code;
        this.name = name;
        this.kind = kind;
        this.amount = amount;
    }
}
