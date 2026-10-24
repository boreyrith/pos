package com.pos.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.BatchSize;

import com.pos.tax.TaxRate;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Anything in the catalog: a product, a service or an item (see {@link OfferingType}). Units and
 * the low-stock threshold only apply to products and items; pricing mode, duration and components
 * only to services. Stock quantities live per storage in {@code stock_levels}.
 */
@Entity
@Table(name = "offerings")
@Getter
@Setter
@NoArgsConstructor
public class Offering {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private OfferingType type;

    @Column(nullable = false, unique = true, length = 40)
    private String sku;

    @Column(unique = true, length = 64)
    private String barcode;

    /** The English name. */
    @Column(nullable = false, length = 150)
    private String name;

    /** The Khmer name. Required when saving; may be missing on entries made before it existed. */
    @Column(name = "name_km", length = 150)
    private String nameKm;

    @Column(length = 1000)
    private String description;

    @Column(name = "has_image", nullable = false)
    private boolean hasImage;

    @Column(name = "image_version", nullable = false)
    private int imageVersion;

    /** The base unit stock is kept in. Null for services. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id")
    private Unit unit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    /** Per unit for products and items; per job or per hour for services. Excludes VAT. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private PricingMode pricing;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal cost = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tax_rate_id")
    private TaxRate taxRate;

    @Column(name = "low_stock_threshold", nullable = false)
    private int lowStockThreshold = 5;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private boolean sellable = true;

    @OneToMany(mappedBy = "service", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<ServiceComponent> components = new ArrayList<>();

    /** Every barcode, the main one first. Loaded in batches so lists don't query once per entry. */
    @OneToMany(mappedBy = "offering", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder")
    @BatchSize(size = 200)
    private List<OfferingBarcode> barcodes = new ArrayList<>();

    @OneToMany(mappedBy = "offering", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("factor")
    private List<OfferingPack> packs = new ArrayList<>();

    /** For packages: what is inside. */
    @OneToMany(mappedBy = "pkg", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder, id")
    private List<PackageLine> packageLines = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Offering(OfferingType type) {
        this.type = type;
    }

    /** Whether it can be put in a cart: active, and for items, marked sellable. */
    public boolean isAvailableForSale() {
        return active && (type != OfferingType.ITEM || sellable);
    }

    public String getUnitSymbol() {
        return unit == null ? null : unit.getSymbol();
    }

    public boolean isHourly() {
        return type == OfferingType.SERVICE && pricing == PricingMode.HOURLY;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** The codes in order, the main one first. */
    public List<String> getBarcodeCodes() {
        return barcodes.stream().map(OfferingBarcode::getCode).toList();
    }

    /** One barcode (or none): replaces the list. */
    public void setBarcode(String code) {
        replaceBarcodes(code == null || code.isBlank() ? List.of() : List.of(code.trim()));
    }

    /**
     * Replaces the barcodes, keeping the rows of codes that stay (so a code is never deleted and
     * inserted again in one flush, which would trip its unique index), and sets the main one.
     */
    public void replaceBarcodes(List<String> codes) {
        Map<String, OfferingBarcode> existing = new HashMap<>();
        barcodes.forEach(b -> existing.put(b.getCode(), b));
        barcodes.removeIf(b -> !codes.contains(b.getCode()));
        for (int i = 0; i < codes.size(); i++) {
            OfferingBarcode b = existing.get(codes.get(i));
            if (b == null) {
                barcodes.add(new OfferingBarcode(this, codes.get(i), i));
            } else {
                b.setSortOrder(i);
            }
        }
        barcodes.sort(Comparator.comparingInt(OfferingBarcode::getSortOrder));
        this.barcode = codes.isEmpty() ? null : codes.get(0);
    }
}
