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

/** Units of one order line taken back, with the amount and tax refunded for them. */
@Entity
@Table(name = "order_return_lines")
@Getter
@NoArgsConstructor
public class OrderReturnLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_id")
    private OrderReturn orderReturn;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id")
    private OrderItem orderItem;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal tax;

    OrderReturnLine(OrderReturn orderReturn, OrderItem orderItem, int quantity, BigDecimal total, BigDecimal tax) {
        this.orderReturn = orderReturn;
        this.orderItem = orderItem;
        this.quantity = quantity;
        this.total = total;
        this.tax = tax;
    }
}
