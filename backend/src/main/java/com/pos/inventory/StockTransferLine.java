package com.pos.inventory;

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

@Entity
@Table(name = "stock_transfer_lines")
@Getter
@Setter
@NoArgsConstructor
public class StockTransferLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transfer_id")
    private StockTransfer transfer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offering_id")
    private Offering offering;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 40)
    private String sku;

    @Column(length = 20)
    private String unit;

    /** In base units. */
    @Column(nullable = false)
    private int quantity;

    public StockTransferLine(StockTransfer transfer, Offering offering, int quantity) {
        this.transfer = transfer;
        this.offering = offering;
        this.name = offering.getName();
        this.sku = offering.getSku();
        this.unit = offering.getUnitSymbol();
        this.quantity = quantity;
    }
}
