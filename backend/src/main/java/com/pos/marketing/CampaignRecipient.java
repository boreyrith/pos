package com.pos.marketing;

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

/** One customer a campaign went to, as they were then, with their message and code. */
@Entity
@Table(name = "campaign_recipients")
@Getter
@NoArgsConstructor
public class CampaignRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "campaign_id")
    private Campaign campaign;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 30)
    private String phone;

    @Column(length = 100)
    private String email;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_code_id")
    private CouponCode couponCode;

    @Column(nullable = false, length = 1200)
    private String message;

    public CampaignRecipient(Campaign campaign, Long customerId, String name, String phone, String email, CouponCode couponCode,
                             String message) {
        this.campaign = campaign;
        this.customerId = customerId;
        this.name = name;
        this.phone = phone;
        this.email = email;
        this.couponCode = couponCode;
        this.message = message;
    }
}
