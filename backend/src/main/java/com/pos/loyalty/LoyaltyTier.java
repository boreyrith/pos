package com.pos.loyalty;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.pos.customer.Tier;

/** A tier's rule: the spend that reaches it and how much faster it earns points. See V29__loyalty_tiers.sql. */
@Entity
@Table(name = "loyalty_tiers")
@Getter
@Setter
@NoArgsConstructor
public class LoyaltyTier {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Tier code;

    @Column(name = "rank_order", nullable = false)
    private int rankOrder;

    @Column(name = "min_spend", nullable = false, precision = 12, scale = 2)
    private BigDecimal minSpend;

    @Column(name = "points_multiplier", nullable = false, precision = 5, scale = 2)
    private BigDecimal pointsMultiplier;
}
