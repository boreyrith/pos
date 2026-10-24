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

/** A pack unit of a product or item: one {@code unit} holds {@code factor} base units. */
@Entity
@Table(name = "offering_packs")
@Getter
@Setter
@NoArgsConstructor
public class OfferingPack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offering_id")
    private Offering offering;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_id")
    private Unit unit;

    @Column(nullable = false)
    private int factor;

    public OfferingPack(Offering offering, Unit unit, int factor) {
        this.offering = offering;
        this.unit = unit;
        this.factor = factor;
    }
}
