package com.pos.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.pos.tax.TaxService;
import com.pos.tax.TaxSettings;
import com.pos.inventory.AdjustmentReason;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record CategoryDto(Long id, String name) {
        static CategoryDto from(Category c) {
            return new CategoryDto(c.getId(), c.getName());
        }
    }

    public record CategoryRequest(@NotBlank @Size(max = 80) String name) {
    }

    /** A part a service uses. {@code itemStock} is the part's stock in the same scope as the service's. */
    public record ComponentDto(Long itemId, String itemName, String sku, String unit, int quantity, int itemStock,
                               boolean itemActive) {
        static ComponentDto from(ServiceComponent c, Map<Long, Integer> stock) {
            Offering item = c.getItem();
            return new ComponentDto(item.getId(), item.getName(), item.getSku(), item.getUnitSymbol(), c.getQuantity(),
                    stock.getOrDefault(item.getId(), 0), item.isActive());
        }
    }

    public record UnitDto(Long id, String symbol, String name) {
        static UnitDto from(Unit u) {
            return new UnitDto(u.getId(), u.getSymbol(), u.getName());
        }
    }

    /** A unit in the units list, with how many catalog entries use it. */
    public record UnitUsageDto(Long id, String symbol, String name, long usage) {
    }

    public record UnitRequest(@NotBlank @Size(max = 20) String symbol, @NotBlank @Size(max = 50) String name) {
    }

    public record PackDto(Long unitId, String symbol, String name, int factor) {
        static PackDto from(OfferingPack p) {
            return new PackDto(p.getUnit().getId(), p.getUnit().getSymbol(), p.getUnit().getName(), p.getFactor());
        }
    }

    /** One line of a package, with its list value (component price x quantity, before tax differences). */
    public record PackageLineDto(Long componentId, OfferingType type, String name, String sku, BigDecimal quantity,
                                 Long unitId, String unit, BigDecimal lineValue, boolean componentActive) {
    }

    /**
     * Package facts for an entry. {@code available}: how many can be made from stock in the
     * request's scope (null if nothing inside is stocked). {@code separatePrice}: what the contents
     * cost on their own. {@code lines} is only filled in by the single-entry endpoint.
     */
    public record PackageInfo(Integer available, boolean containsServices, BigDecimal separatePrice,
                              List<PackageLineDto> lines) {
    }

    /**
     * A catalog entry. Stock, units and packs are null for services. {@code stock} is in the base
     * unit, summed over the storages the request looked at (see the offerings endpoint).
     * {@code packs} and {@code components} are only filled in by the single-entry endpoint.
     */
    /** {@code barcode} is the main one (printed on labels); {@code barcodes} lists them all, main first. */
    public record OfferingDto(Long id, OfferingType type, String sku, String barcode, List<String> barcodes, String name, String nameKm,
                              String description, boolean hasImage, int imageVersion, Long unitId,
                              String unit, Long categoryId, String categoryName, BigDecimal price,
                              PricingMode pricing, Integer durationMinutes, BigDecimal cost, Long taxRateId,
                              String taxRateName, BigDecimal taxRate, String effectiveTaxName, BigDecimal effectiveTaxRate, boolean includesTax,
                              Integer stock, Integer lowStockThreshold, boolean active, boolean sellable,
                              List<PackDto> packs, List<ComponentDto> components, PackageInfo packageInfo) {

        /** @param taxSettings the store's tax mode and scope, which decide the rate actually charged */
        static OfferingDto from(Offering o, Map<Long, Integer> stock, TaxSettings taxSettings, PackageInfo pkg) {
            return from(o, stock, taxSettings, null, null, pkg);
        }

        static OfferingDto detailed(Offering o, Map<Long, Integer> stock, TaxSettings taxSettings, PackageInfo pkg) {
            return from(o, stock, taxSettings, o.getPacks().stream().map(PackDto::from).toList(),
                    o.getComponents().stream().map(c -> ComponentDto.from(c, stock)).toList(), pkg);
        }

        private static OfferingDto from(Offering o, Map<Long, Integer> stock, TaxSettings taxSettings,
                                        List<PackDto> packs, List<ComponentDto> components, PackageInfo pkg) {
            var tax = o.getTaxRate();
            var key = TaxService.keyFor(o, taxSettings);
            Category c = o.getCategory();
            Unit u = o.getUnit();
            boolean stocked = o.getType().tracksStock();
            return new OfferingDto(o.getId(), o.getType(), o.getSku(), o.getBarcode(), o.getBarcodeCodes(), o.getName(), o.getNameKm(),
                    o.getDescription(), o.isHasImage(), o.getImageVersion(),
                    u == null ? null : u.getId(), u == null ? null : u.getSymbol(),
                    c == null ? null : c.getId(), c == null ? null : c.getName(),
                    o.getPrice(), o.getPricing(), o.getDurationMinutes(), o.getCost(), tax.getId(), tax.getName(),
                    tax.getRate(), key.name(), key.rate(), key.included(),
                    stocked ? stock.getOrDefault(o.getId(), 0) : null, stocked ? o.getLowStockThreshold() : null,
                    o.isActive(), o.isSellable(), packs, components, pkg);
        }
    }

    public record ComponentRequest(@NotNull Long itemId, @Min(1) @Max(1000) int quantity) {
    }

    /**
     * A package line: {@code quantity} of the component in {@code unitId} (a base or pack unit for
     * products and items; omit for services, where it means jobs or hours, and for packages).
     */
    public record PackageLineRequest(@NotNull Long componentId,
                                     @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3) BigDecimal quantity,
                                     Long unitId) {
    }

    /** One {@code unitId} holds {@code factor} base units. */
    public record PackRequest(@NotNull Long unitId, @Min(2) @Max(1_000_000) int factor) {
    }

    /**
     * Create or update a catalog entry. {@code type} cannot change after creation. A blank SKU is
     * generated. Stock (in the base unit) is set here only on create; use the stock endpoint
     * afterwards. Base unit and packs are for products and items (base unit defaults to pcs);
     * pricing mode, duration and components are for services; {@code sellable} is for items.
     */
    public record OfferingRequest(
            @NotNull OfferingType type,
            @Size(max = 40) String sku,
            /** One barcode; older clients. Ignored when {@code barcodes} is given. */
            @Size(max = 64) String barcode,
            /** Every barcode, the main one (printed on labels) first. Each must be unique across the catalog. */
            @Size(max = 20) List<@Size(max = 64) String> barcodes,
            /** English name. */
            @NotBlank @Size(max = 150) String name,
            /** Khmer name. */
            @NotBlank @Size(max = 150) String nameKm,
            @Size(max = 1000) String description,
            Long unitId,
            @Size(max = 20) List<@Valid PackRequest> packs,
            Long categoryId,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
            PricingMode pricing,
            @Min(1) @Max(1440) Integer durationMinutes,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal cost,
            /** Null uses the default tax rate. */
            Long taxRateId,
            @Min(0) Integer stock,
            @Min(0) Integer lowStockThreshold,
            Boolean active,
            Boolean sellable,
            @Size(max = 50) List<@Valid ComponentRequest> components,
            /** For packages: what is inside. */
            @Size(max = 50) List<@Valid PackageLineRequest> contents) {
    }

    public enum StockOperation {
        /** Goods arrived: add. */
        RECEIVE,
        /** Damaged, lost or used in-house: take away. */
        REMOVE,
        /** A stock-take: the counted amount becomes the stock. */
        COUNT
    }

    /** An amount in one of the entry's units (its base unit or one of its packs). */
    public record UnitQuantity(@NotNull Long unitId, @Min(0) @Max(1_000_000) int quantity) {
    }

    /** Changes stock in one storage by amounts in any of the entry's units, e.g. 3 carton + 5 pcs. */
    /** {@code reason} is required for REMOVE, defaults to "Goods received" for RECEIVE, and is ignored for COUNT. */
    public record StockChangeRequest(@NotNull Long storageId, @NotNull StockOperation operation,
                                     @NotEmpty @Size(max = 20) List<@Valid UnitQuantity> quantities,
                                     @Size(max = 255) String note, AdjustmentReason reason) {
    }

    /** An entry's quantity in one storage. */
    public record StockLevelDto(Long branchId, String branchName, Long storageId, String storageName, boolean selling,
                                boolean storageActive, int quantity) {
    }
}
