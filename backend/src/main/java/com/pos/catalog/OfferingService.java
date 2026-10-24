package com.pos.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.tax.TaxSettings;
import com.pos.catalog.CatalogDtos.ComponentRequest;
import com.pos.catalog.CatalogDtos.OfferingDto;
import com.pos.catalog.CatalogDtos.OfferingRequest;
import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.Changes;
import com.pos.auth.AuthUser;
import com.pos.catalog.CatalogDtos.PackRequest;
import com.pos.catalog.CatalogDtos.PackageInfo;
import com.pos.catalog.CatalogDtos.PackageLineDto;
import com.pos.catalog.CatalogDtos.PackageLineRequest;
import com.pos.common.ImageTypes;
import com.pos.common.Money;
import com.pos.catalog.CatalogDtos.StockChangeRequest;
import com.pos.catalog.CatalogDtos.StockOperation;
import com.pos.catalog.CatalogDtos.StockLevelDto;
import com.pos.common.ApiException;
import com.pos.inventory.AdjustmentKind;
import com.pos.inventory.AdjustmentReason;
import com.pos.inventory.AdjustmentService;
import com.pos.inventory.BranchContext;
import com.pos.inventory.MovementType;
import com.pos.inventory.StockService;
import com.pos.inventory.StockService.Ref;
import com.pos.inventory.Storage;
import com.pos.inventory.StorageRepository;
import com.pos.settings.SettingsService;
import com.pos.tax.TaxRate;
import com.pos.tax.TaxRateRepository;

@Service
public class OfferingService {

    private final OfferingRepository offerings;
    private final CategoryRepository categories;
    private final UnitRepository units;
    private final StockService stock;
    private final BranchContext branches;
    private final StorageRepository storages;
    private final TaxRateRepository taxRates;
    private final SettingsService settings;
    private final AuditService audit;
    private final OfferingImageRepository images;
    private final AdjustmentService adjustmentService;

    private final OfferingBarcodeRepository barcodes;

    public OfferingService(OfferingRepository offerings, CategoryRepository categories, UnitRepository units,
                           StockService stock, BranchContext branches, StorageRepository storages,
                           TaxRateRepository taxRates, SettingsService settings, AuditService audit,
                           OfferingImageRepository images, AdjustmentService adjustmentService,
                           OfferingBarcodeRepository barcodes) {
        this.barcodes = barcodes;
        this.audit = audit;
        this.images = images;
        this.adjustmentService = adjustmentService;
        this.taxRates = taxRates;
        this.settings = settings;
        this.offerings = offerings;
        this.categories = categories;
        this.units = units;
        this.stock = stock;
        this.branches = branches;
        this.storages = storages;
    }

    /** Stock in the results is summed over {@code storageIds}; so is the low-stock filter. */
    @Transactional(readOnly = true)
    public Page<OfferingDto> search(String q, Set<OfferingType> types, Long categoryId, boolean activeOnly,
                                    boolean forSale, boolean lowStock, List<Long> storageIds, List<Long> ids, Pageable pageable) {
        String term = q == null || q.isBlank() ? null : q.trim();
        Set<OfferingType> filter = types == null || types.isEmpty() ? EnumSet.allOf(OfferingType.class) : types;
        // An empty IN () list is invalid SQL; -1 matches no storage.
        List<Long> scope = storageIds.isEmpty() ? List.of(-1L) : storageIds;
        boolean anyId = ids == null || ids.isEmpty();
        var page = offerings.search(term, filter, categoryId, activeOnly, forSale, lowStock, scope, anyId,
                anyId ? List.of(-1L) : ids, pageable);
        Map<Long, Integer> totals = stock.totals(page.getContent().stream().map(Offering::getId).toList(), scope);
        return page.map(o -> OfferingDto.from(o, totals, taxSettings(), packageInfo(o, scope, false)));
    }

    @Transactional(readOnly = true)
    public OfferingDto get(Long id, List<Long> storageIds) {
        return detailed(findWithComponents(id), storageIds);
    }

    /** Where an entry is stocked, in every storage of every active branch. */
    @Transactional(readOnly = true)
    public List<StockLevelDto> levels(Long id) {
        findWithComponents(id);
        return stock.levels(id).stream()
                .map(l -> new StockLevelDto(l.branchId(), l.branchName(), l.storageId(), l.storageName(), l.selling(),
                        l.storageActive(), l.quantity()))
                .toList();
    }

    /** For the sell screen's scanner: a barcode, or a SKU (e.g. from a QR label). Only what can be sold. */
    @Transactional(readOnly = true)
    public OfferingDto forSaleByBarcode(String code, List<Long> storageIds) {
        String c = code.trim();
        Offering o = offerings.findByScannedCode(c).stream()
                .filter(Offering::isAvailableForSale)
                .sorted(Comparator.comparing((Offering x) -> !x.getBarcodeCodes().contains(c)))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Nothing for sale with code " + c));
        return OfferingDto.from(o, stock.totals(List.of(o.getId()), storageIds), taxSettings(),
                packageInfo(o, storageIds, false));
    }

    /** Opening stock goes into the current branch's selling storage. */
    @Transactional
    public OfferingDto create(OfferingRequest req, AuthUser user) {
        Offering o = new Offering(req.type());
        o.setSku(blank(req.sku()) ? generateSku(req.type()) : req.sku().trim().toUpperCase());
        if (req.type() == OfferingType.ITEM) {
            // Parts are for internal use unless explicitly offered at the till.
            o.setSellable(Boolean.TRUE.equals(req.sellable()));
        }
        apply(o, req);
        offerings.saveAndFlush(o);
        if (req.type().tracksStock() && req.stock() != null && req.stock() > 0) {
            Storage selling = branches.sellingStorage(user);
            stock.add(selling.getId(), o.getId(), req.stock(), MovementType.OPENING,
                    new Ref(user.id(), null, null, "Opening stock"));
        }
        audit.log(AuditAction.OFFERING_CREATED, "Offering", o.getId(), "Added " + label(o) + " " + o.getName() + " ("
                + o.getSku() + ") at " + o.getPrice().stripTrailingZeros().toPlainString());
        return detailed(o, branches.storageIds(branches.currentBranchId(user)));
    }

    @Transactional
    public OfferingDto update(Long id, OfferingRequest req, List<Long> storageIds) {
        Offering o = findWithComponents(id);
        Map<String, String> before = snapshot(o);
        if (o.getType() != req.type()) {
            throw ApiException.badRequest("The type of a catalog entry can't be changed. Create a new one instead.");
        }
        if (!blank(req.sku())) {
            o.setSku(req.sku().trim().toUpperCase());
        }
        if (req.type() == OfferingType.ITEM && req.sellable() != null) {
            o.setSellable(req.sellable());
        }
        apply(o, req);
        offerings.saveAndFlush(o);
        Map<String, String> after = snapshot(o);
        Changes changes = new Changes();
        before.forEach((field, value) -> changes.add(field, value, after.get(field)));
        if (!changes.isEmpty()) {
            audit.log(AuditAction.OFFERING_UPDATED, "Offering", o.getId(), o.getName() + ": " + changes.describe(), changes);
        }
        return detailed(o, storageIds);
    }

    /** The fields worth auditing, as text. Parts and packs are summarised. */
    private static Map<String, String> snapshot(Offering o) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Name", o.getName());
        m.put("Khmer name", o.getNameKm());
        m.put("Description", o.getDescription());
        m.put("SKU", o.getSku());
        m.put("Barcodes", o.getBarcodes().isEmpty() ? null : String.join(", ", o.getBarcodeCodes()));
        m.put("Category", o.getCategory() == null ? null : o.getCategory().getName());
        m.put("Price", o.getPrice() == null ? null : o.getPrice().stripTrailingZeros().toPlainString());
        m.put("Cost", o.getCost() == null ? null : o.getCost().stripTrailingZeros().toPlainString());
        m.put("Tax rate", o.getTaxRate() == null ? null : o.getTaxRate().getName());
        m.put("Pricing", o.getPricing() == null ? null : o.getPricing().name().toLowerCase());
        m.put("Duration (min)", o.getDurationMinutes() == null ? null : o.getDurationMinutes().toString());
        m.put("Unit", o.getUnitSymbol());
        m.put("Low-stock alert", o.getType().tracksStock() ? String.valueOf(o.getLowStockThreshold()) : null);
        m.put("Active", o.isActive() ? "yes" : "no");
        m.put("Sold at till", o.getType() == OfferingType.ITEM ? (o.isSellable() ? "yes" : "no") : null);
        m.put("Packs", o.getPacks().stream().map(p -> p.getUnit().getSymbol() + "=" + p.getFactor()).sorted()
                .reduce((a, b) -> a + ", " + b).orElse(null));
        m.put("Contents", o.getPackageLines().stream()
                .map(l -> l.getQuantity().stripTrailingZeros().toPlainString() + (l.getUnit() == null ? "" : " " + l.getUnit().getSymbol())
                        + " " + l.getComponent().getName())
                .reduce((a, b) -> a + ", " + b).orElse(null));
        m.put("Parts", o.getComponents().stream().map(c -> c.getQuantity() + " " + c.getItem().getName()).sorted()
                .reduce((a, b) -> a + ", " + b).orElse(null));
        return m;
    }

    private static String label(Offering o) {
        return o.getType().name().toLowerCase();
    }

    /**
     * Receives, removes or counts stock in one storage, in any of the entry's units. Recorded as a
     * one-line stock adjustment (or count), with its reason, in stock history and the audit log.
     */
    @Transactional
    public OfferingDto changeStock(Long id, StockChangeRequest req, AuthUser user) {
        Offering o = findWithComponents(id);
        if (!o.getType().tracksStock()) {
            throw ApiException.badRequest(o.getName() + " has no stock of its own. Adjust the stock of what it uses.");
        }
        AdjustmentReason reason = switch (req.operation()) {
            case RECEIVE -> req.reason() == null ? AdjustmentReason.RECEIVED : req.reason();
            case REMOVE -> {
                if (req.reason() == null) {
                    throw ApiException.badRequest("Choose why the stock is being removed");
                }
                yield req.reason();
            }
            case COUNT -> AdjustmentReason.COUNT;
        };
        var operation = switch (req.operation()) {
            case RECEIVE -> AdjustmentService.Operation.INCREASE;
            case REMOVE -> AdjustmentService.Operation.DECREASE;
            case COUNT -> AdjustmentService.Operation.SET;
        };
        boolean count = req.operation() == StockOperation.COUNT;
        adjustmentService.create(new AdjustmentService.AdjustmentRequest(req.storageId(),
                count ? AdjustmentKind.COUNT : AdjustmentKind.ADJUSTMENT, null,
                List.of(new AdjustmentService.LineRequest(id, operation, req.quantities(), reason, req.note(), null))), user);
        return detailed(o, List.of(req.storageId()));
    }

    public static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;

    /** Stores an entry's image (PNG, JPEG or WebP up to 2 MB), replacing any earlier one. */
    @Transactional
    public OfferingDto setImage(Long id, byte[] bytes, List<Long> storageIds) {
        Offering o = findWithComponents(id);
        if (bytes.length == 0) {
            throw ApiException.badRequest("Choose an image");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw ApiException.badRequest("The image must be 2 MB or smaller");
        }
        String type = ImageTypes.sniff(bytes);
        if (type == null) {
            throw ApiException.badRequest("The image must be a PNG, JPEG or WebP file");
        }
        OfferingImage image = images.findById(id).orElseGet(() -> new OfferingImage(id));
        image.setData(bytes);
        image.setContentType(type);
        image.setSizeBytes(bytes.length);
        image.setUpdatedAt(Instant.now());
        images.save(image);
        boolean replaced = o.isHasImage();
        o.setHasImage(true);
        o.setImageVersion(o.getImageVersion() + 1);
        audit.log(AuditAction.OFFERING_UPDATED, "Offering", id, o.getName() + ": " + (replaced ? "replaced" : "added")
                + " the image (" + type + ", " + (bytes.length / 1024) + " KB)");
        return detailed(o, storageIds);
    }

    @Transactional
    public OfferingDto removeImage(Long id, List<Long> storageIds) {
        Offering o = findWithComponents(id);
        if (o.isHasImage()) {
            images.deleteById(id);
            o.setHasImage(false);
            o.setImageVersion(o.getImageVersion() + 1);
            audit.log(AuditAction.OFFERING_UPDATED, "Offering", id, o.getName() + ": removed the image");
        }
        return detailed(o, storageIds);
    }

    /** The image bytes and type, or empty if the entry has none. */
    @Transactional(readOnly = true)
    public Optional<OfferingImage> image(Long id) {
        return images.findById(id).map(i -> {
            i.getData(); // load the lazy bytes inside the transaction
            return i;
        });
    }

    private TaxSettings taxSettings() {
        return settings.current().tax();
    }

    /** The requested rate, or for a new entry the default. A turned-off rate can't be newly chosen. */
    private TaxRate taxRate(Long requestedId, TaxRate current) {
        if (requestedId == null) {
            return current != null ? current
                    : taxRates.findByDefaultRateTrue().orElseThrow(() -> ApiException.badRequest("Set up a default tax rate first"));
        }
        TaxRate rate = taxRates.findById(requestedId).orElseThrow(() -> ApiException.notFound("Tax rate"));
        if (!rate.isActive() && (current == null || !current.getId().equals(rate.getId()))) {
            throw ApiException.badRequest(rate.getName() + " is turned off");
        }
        return rate;
    }

    private OfferingDto detailed(Offering o, List<Long> storageIds) {
        List<Long> ids = new ArrayList<>(List.of(o.getId()));
        o.getComponents().forEach(c -> ids.add(c.getItem().getId()));
        return OfferingDto.detailed(o, stock.totals(ids, storageIds), taxSettings(), packageInfo(o, storageIds, true));
    }

    /** For packages: availability from stock in scope, list value of the contents, and the lines if asked. */
    private PackageInfo packageInfo(Offering o, List<Long> storageIds, boolean withLines) {
        if (o.getType() != OfferingType.PACKAGE) {
            return null;
        }
        var expansion = PackageExpander.expand(o, 1);
        Map<Long, Integer> onHand = stock.totals(expansion.stock().keySet(), storageIds.isEmpty() ? List.of(-1L) : storageIds);
        BigDecimal separate = BigDecimal.ZERO;
        List<PackageLineDto> lines = new ArrayList<>();
        for (PackageLine l : o.getPackageLines()) {
            Offering c = l.getComponent();
            int factor = l.getUnit() == null || !c.getType().tracksStock() ? 1 : UnitConversion.factorOf(c, l.getUnit().getId());
            BigDecimal value = Money.round(c.getPrice().multiply(l.getQuantity()).multiply(BigDecimal.valueOf(factor)));
            separate = separate.add(value);
            if (withLines) {
                lines.add(new PackageLineDto(c.getId(), c.getType(), c.getName(), c.getSku(), l.getQuantity(),
                        l.getUnit() == null ? null : l.getUnit().getId(),
                        l.getUnit() != null ? l.getUnit().getSymbol() : c.isHourly() ? "h" : c.getType() == OfferingType.SERVICE ? "job" : c.getUnitSymbol(),
                        value, c.isActive()));
            }
        }
        return new PackageInfo(PackageExpander.available(expansion.stock(), onHand), expansion.containsServices(), separate,
                withLines ? lines : null);
    }

    private void apply(Offering o, OfferingRequest req) {
        OfferingType type = o.getType();
        boolean service = type == OfferingType.SERVICE;
        boolean pkg = type == OfferingType.PACKAGE;
        List<ComponentRequest> components = req.components() == null ? List.of() : req.components();
        List<PackageLineRequest> contents = req.contents() == null ? List.of() : req.contents();
        if (!pkg && !contents.isEmpty()) {
            throw ApiException.badRequest("Only packages have contents");
        }
        if (pkg && ((req.packs() != null && !req.packs().isEmpty()) || req.stock() != null && req.stock() > 0)) {
            throw ApiException.badRequest("Packages have no stock or pack sizes of their own; they use their contents' stock");
        }

        if (!service && (req.pricing() != null || req.durationMinutes() != null || !components.isEmpty())) {
            throw ApiException.badRequest("Pricing mode, duration and parts used only apply to services");
        }
        if (service && (req.unitId() != null || (req.packs() != null && !req.packs().isEmpty()))) {
            throw ApiException.badRequest("Services don't have units or packs");
        }

        o.setName(req.name().trim());
        o.setNameKm(req.nameKm().trim());
        o.setDescription(blank(req.description()) ? null : req.description().trim());
        if (!service) {
            if (req.unitId() != null) {
                o.setUnit(units.findById(req.unitId()).orElseThrow(() -> ApiException.notFound("Unit")));
            } else if (o.getUnit() == null) {
                // Packages are usually sold as a "set"; everything else is counted in pieces.
                o.setUnit((pkg ? units.findBySymbol("set") : Optional.<Unit>empty())
                        .or(() -> units.findBySymbol("pcs"))
                        .orElseThrow(() -> ApiException.badRequest("Choose a unit")));
            }
            if (req.packs() != null) {
                replacePacks(o, req.packs());
            }
            if (o.getPacks().stream().anyMatch(p -> p.getUnit().getId().equals(o.getUnit().getId()))) {
                throw ApiException.badRequest("A pack can't use the same unit as the base unit");
            }
        }
        applyBarcodes(o, req);
        o.setCategory(req.categoryId() == null ? null
                : categories.findById(req.categoryId()).orElseThrow(() -> ApiException.notFound("Category")));
        o.setPrice(req.price());
        o.setCost(req.cost() == null ? BigDecimal.ZERO : req.cost());
        o.setTaxRate(taxRate(req.taxRateId(), o.getTaxRate()));
        if (req.active() != null) {
            o.setActive(req.active());
        }
        if (type.tracksStock() && req.lowStockThreshold() != null) {
            o.setLowStockThreshold(req.lowStockThreshold());
        }
        if (service) {
            o.setPricing(req.pricing() == null ? PricingMode.FIXED : req.pricing());
            o.setDurationMinutes(req.durationMinutes());
            replaceComponents(o, components);
        }
        if (pkg) {
            replacePackageLines(o, contents);
        }
    }

    /** Validates and stores a package's contents, then checks it can be expanded (no loops, whole stock units). */
    private void replacePackageLines(Offering pkg, List<PackageLineRequest> requested) {
        if (requested.isEmpty()) {
            throw ApiException.badRequest("Add at least one thing to the package");
        }
        Set<Long> seen = new HashSet<>();
        List<PackageLine> lines = new ArrayList<>();
        int order = 0;
        for (PackageLineRequest r : requested) {
            if (!seen.add(r.componentId())) {
                throw ApiException.badRequest("Each entry can only be listed once; change its quantity instead");
            }
            if (pkg.getId() != null && pkg.getId().equals(r.componentId())) {
                throw ApiException.badRequest("A package can't contain itself");
            }
            Offering c = offerings.findWithComponentsById(r.componentId()).orElseThrow(() -> ApiException.notFound("Package content"));
            if (!c.isActive() && pkg.getPackageLines().stream().noneMatch(l -> l.getComponent().getId().equals(c.getId()))) {
                throw ApiException.badRequest(c.getName() + " is hidden and can't be added");
            }
            Unit unit = null;
            switch (c.getType()) {
                case PRODUCT, ITEM -> {
                    unit = r.unitId() == null ? c.getUnit() : units.findById(r.unitId()).orElseThrow(() -> ApiException.notFound("Unit"));
                    UnitConversion.factorOf(c, unit.getId()); // must be its base unit or one of its packs
                }
                case SERVICE -> {
                    if (r.unitId() != null) {
                        throw ApiException.badRequest(c.getName() + " is a service: give its quantity in " + (c.isHourly() ? "hours" : "jobs"));
                    }
                    if (!c.isHourly() && r.quantity().stripTrailingZeros().scale() > 0) {
                        throw ApiException.badRequest(c.getName() + " is charged per job, so its quantity must be a whole number");
                    }
                }
                case PACKAGE -> {
                    if (r.unitId() != null || r.quantity().stripTrailingZeros().scale() > 0) {
                        throw ApiException.badRequest("A package inside a package needs a whole-number quantity and no unit");
                    }
                }
            }
            lines.add(new PackageLine(pkg, c, r.quantity(), unit, order++));
        }
        // Clear then flush so the unique (package, component) key isn't hit when lines are re-added.
        pkg.getPackageLines().clear();
        if (pkg.getId() != null) {
            offerings.flush();
        }
        pkg.getPackageLines().addAll(lines);
        PackageExpander.expand(pkg, 1);
    }

    private void replacePacks(Offering o, List<PackRequest> requested) {
        Set<Long> seen = new HashSet<>();
        for (PackRequest p : requested) {
            if (!seen.add(p.unitId())) {
                throw ApiException.badRequest("Each unit can only be one pack size");
            }
        }
        var found = units.findAllById(seen);
        if (found.size() != seen.size()) {
            throw ApiException.notFound("A pack unit");
        }
        // Clear then flush so the unique (offering, unit) constraint isn't hit when re-adding a pack.
        o.getPacks().clear();
        if (o.getId() != null) {
            offerings.flush();
        }
        for (PackRequest p : requested) {
            Unit unit = found.stream().filter(u -> u.getId().equals(p.unitId())).findFirst().orElseThrow();
            o.getPacks().add(new OfferingPack(o, unit, p.factor()));
        }
    }

    private void replaceComponents(Offering service, List<ComponentRequest> requested) {
        Set<Long> seen = new HashSet<>();
        for (ComponentRequest c : requested) {
            if (!seen.add(c.itemId())) {
                throw ApiException.badRequest("Each item can only be listed once in a service's parts");
            }
        }
        var items = offerings.findAllById(seen);
        if (items.size() != seen.size()) {
            throw ApiException.notFound("A part");
        }
        for (Offering item : items) {
            if (item.getType() != OfferingType.ITEM) {
                throw ApiException.badRequest(item.getName() + " is not an item. Services can only use items as parts.");
            }
        }
        // Clear then flush so the unique (service, item) constraint isn't hit when re-adding a part.
        service.getComponents().clear();
        if (service.getId() != null) {
            offerings.flush();
        }
        for (ComponentRequest c : requested) {
            Offering item = items.stream().filter(i -> i.getId().equals(c.itemId())).findFirst().orElseThrow();
            service.getComponents().add(new ServiceComponent(service, item, c.quantity()));
        }
    }

    private Offering findWithComponents(Long id) {
        return offerings.findWithComponentsById(id).orElseThrow(() -> ApiException.notFound("Catalog entry"));
    }

    private String generateSku(OfferingType type) {
        String sku;
        do {
            String prefix = switch (type) {
                case PRODUCT -> "P";
                case SERVICE -> "S";
                case ITEM -> "I";
                case PACKAGE -> "K";
            };
            sku = prefix + ThreadLocalRandom.current().nextInt(100_000, 1_000_000);
        } while (offerings.existsBySku(sku));
        return sku;
    }

    /** Letters, digits and . - _ only, so every scanner and label printer can carry the code. */
    private static final Pattern BARCODE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /**
     * Sets an entry's barcodes, the main one first. Each must be unique across the catalog and must
     * not be another entry's SKU (scanning matches both).
     */
    private void applyBarcodes(Offering o, OfferingRequest req) {
        List<String> wanted = req.barcodes() != null ? req.barcodes()
                : blank(req.barcode()) ? List.of() : List.of(req.barcode());
        List<String> codes = new ArrayList<>();
        for (String raw : wanted) {
            if (blank(raw)) {
                continue;
            }
            String code = raw.trim();
            if (!BARCODE.matcher(code).matches()) {
                throw ApiException.badRequest("Barcode " + code + " can only have letters, numbers, dots, dashes and underscores");
            }
            if (codes.contains(code)) {
                throw ApiException.badRequest("Barcode " + code + " is listed twice");
            }
            codes.add(code);
        }
        for (OfferingBarcode b : codes.isEmpty() ? List.<OfferingBarcode>of() : barcodes.findByCodeIn(codes)) {
            if (!b.getOffering().getId().equals(o.getId())) {
                throw new ApiException(HttpStatus.CONFLICT, "Barcode " + b.getCode() + " is already used by "
                        + b.getOffering().getName() + " (" + b.getOffering().getSku() + ")", "BARCODE_TAKEN");
            }
        }
        for (String code : codes) {
            offerings.findFirstBySkuIgnoreCase(code).filter(other -> !other.getId().equals(o.getId())).ifPresent(other -> {
                throw new ApiException(HttpStatus.CONFLICT, "Barcode " + code + " is the SKU of " + other.getName(), "BARCODE_TAKEN");
            });
        }
        o.replaceBarcodes(codes);
    }

    /** Unused in-store EAN-13 codes (prefix 200), next in sequence. */
    @Transactional(readOnly = true)
    public List<String> nextBarcodes(int count) {
        String prefix = Barcodes.IN_STORE_PREFIX;
        List<String> top = barcodes.findInStoreCodes(prefix, PageRequest.of(0, 1));
        long next = top.isEmpty() ? 1 : Long.parseLong(top.get(0).substring(prefix.length(), 12)) + 1;
        List<String> out = new ArrayList<>();
        while (out.size() < count) {
            String code = Barcodes.ean13(prefix + String.format("%09d", next++));
            if (!barcodes.existsByCode(code) && offerings.findFirstBySkuIgnoreCase(code).isEmpty()) {
                out.add(code);
            }
        }
        return out;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
