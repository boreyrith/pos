package com.pos.catalog;

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

/** An item a service uses up each time one unit of the service is sold. */
@Entity
@Table(name = "service_components")
@Getter
@Setter
@NoArgsConstructor
public class ServiceComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id")
    private Offering service;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id")
    private Offering item;

    @Column(nullable = false)
    private int quantity;

    public ServiceComponent(Offering service, Offering item, int quantity) {
        this.service = service;
        this.item = item;
        this.quantity = quantity;
    }
}
