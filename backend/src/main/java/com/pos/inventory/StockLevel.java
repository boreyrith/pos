package com.pos.inventory;

import java.io.Serializable;

import com.pos.catalog.Offering;

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

/**
 * The quantity of one entry in one storage, in base units. Read-only in JPA (used by queries);
 * {@link StockService} changes quantities with SQL.
 */
@Entity
@Table(name = "stock_levels")
@Getter
@NoArgsConstructor
public class StockLevel {

    @Embeddable
    @Getter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        @Column(name = "storage_id")
        private Long storageId;

        @Column(name = "offering_id")
        private Long offeringId;
    }

    @EmbeddedId
    private Key id;

    @MapsId("storageId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_id")
    private Storage storage;

    @MapsId("offeringId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offering_id")
    private Offering offering;

    @Column(nullable = false)
    private int quantity;
}
