package com.pos.sales;

import com.pos.catalog.Offering;

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
import lombok.Setter;

/** A part a sold service used up, in total for the whole line. */
@Entity
@Table(name = "order_item_components")
@Getter
@Setter
@NoArgsConstructor
public class OrderItemComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id")
    private OrderItem orderItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id")
    private Offering item;

    @Column(name = "item_name", nullable = false, length = 150)
    private String itemName;

    @Column(nullable = false)
    private int quantity;

    public OrderItemComponent(OrderItem orderItem, Offering item, int quantity) {
        this.orderItem = orderItem;
        this.item = item;
        this.itemName = item.getName();
        this.quantity = quantity;
    }
}
