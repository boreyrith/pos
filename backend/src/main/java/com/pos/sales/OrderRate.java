package com.pos.sales;

import java.io.Serializable;
import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A rate an order was sold at, so its receipt shows the same converted totals later. */
@Entity
@Table(name = "order_rates")
@Getter
@NoArgsConstructor
public class OrderRate {

    @Embeddable
    @Getter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        @Column(name = "order_id")
        private Long orderId;

        @Column(name = "currency_code", length = 3)
        private String currency;

        Key(String currency) {
            this.currency = currency;
        }
    }

    @EmbeddedId
    private Key id;

    @MapsId("orderId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal rate;

    OrderRate(Order order, String currency, BigDecimal rate) {
        this.id = new Key(currency);
        this.order = order;
        this.rate = rate;
    }

    public String getCurrency() {
        return id.getCurrency();
    }
}
