package com.pos.loyalty;

import java.math.BigDecimal;
import java.time.Instant;

import com.pos.auth.User;

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

/** The store's loyalty rules: one row (id 1). See V27__loyalty.sql. */
@Entity
@Table(name = "loyalty_settings")
@Getter
@Setter
@NoArgsConstructor
public class LoyaltySettings {

    @Id
    private Long id;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "points_per_unit", nullable = false, precision = 10, scale = 4)
    private BigDecimal pointsPerUnit;

    @Column(name = "point_value", nullable = false, precision = 10, scale = 4)
    private BigDecimal pointValue;

    @Column(name = "min_redeem", nullable = false)
    private int minRedeem;

    @Column(name = "max_redeem_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxRedeemPct;

    /** Months of spending that count towards a tier; null means all time. */
    @Column(name = "tier_months")
    private Integer tierMonths;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
