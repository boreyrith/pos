package com.pos.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.auth.AuthUser;
import com.pos.auth.UserRepository;
import com.pos.catalog.CatalogDtos.UnitQuantity;
import com.pos.catalog.Offering;
import com.pos.catalog.OfferingRepository;
import com.pos.catalog.OfferingType;
import com.pos.catalog.UnitConversion;
import com.pos.common.ApiException;
import com.pos.common.Money;
import com.pos.inventory.StockService.Ref;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Stock adjustments and counts. Each request becomes one numbered document, applied at once with
 * single-statement stock updates, so a sale at the same moment is never overwritten. Every line
 * keeps before / change / after and the cost value of the change; every change is in stock history.
 */
@Service
public class AdjustmentService {

    public enum Operation { INCREASE, DECREASE, SET }

    /**
     * One entry: INCREASE or DECREASE by, or SET to, the given amount (in any of its units).
     * {@code expected}: for counts, the quantity the count sheet showed.
     */
    public record LineRequest(@NotNull Long offeringId, @NotNull Operation operation,
                              @NotEmpty @Size(max = 20) List<@Valid UnitQuantity> quantities,
                              AdjustmentReason reason, @Size(max = 255) String note, Integer expected) {
    }

    public record AdjustmentRequest(@NotNull Long storageId, @NotNull AdjustmentKind kind, @Size(max = 255) String note,
                                    @NotEmpty @Size(max = 1000) List<@Valid LineRequest> lines) {
    }

    public record LineDto(Long offeringId, String name, String sku, String unit, AdjustmentReason reason, String reasonLabel,
                          String note, int beforeQty, int changeQty, int afterQty, Integer expectedQty, BigDecimal unitCost,
                          BigDecimal valueChange) {
        static LineDto from(StockAdjustmentLine l) {
            return new LineDto(l.getOffering().getId(), l.getName(), l.getSku(), l.getUnit(), l.getReason(),
                    l.getReason().label(), l.getNote(), l.getBeforeQty(), l.getChangeQty(), l.getAfterQty(), l.getExpectedQty(),
                    l.getUnitCost(), l.getValueChange());
        }
    }

    /** {@code lines} is only filled in for a single adjustment. */
    public record AdjustmentDto(Long id, String adjustmentNumber, AdjustmentKind kind, InventoryDtos.PlaceDto place,
                                String note, int lineCount, BigDecimal valueChange, String createdBy, Instant createdAt,
                                List<LineDto> lines) {
        static AdjustmentDto from(StockAdjustment a, boolean withLines) {
            return new AdjustmentDto(a.getId(), a.getAdjustmentNumber(), a.getKind(), InventoryDtos.PlaceDto.from(a.getStorage()),
                    a.getNote(), a.getLineCount(), a.getValueChange(), a.getCreatedBy().getFullName(), a.getCreatedAt(),
                    withLines ? a.getLines().stream().map(LineDto::from).toList() : null);
        }
    }

    /** A row of a count sheet: what the system has in the storage now. */
    public record SheetRow(Long offeringId, OfferingType type, String name, String nameKm, String sku, String unit,
                           String categoryName, int systemQty, BigDecimal unitCost) {
    }

    private final StockAdjustmentRepository adjustments;
    private final StorageRepository storages;
    private final OfferingRepository offerings;
    private final UserRepository users;
    private final StockService stock;
    private final BranchContext branches;
    private final AuditService audit;

    public AdjustmentService(StockAdjustmentRepository adjustments, StorageRepository storages, OfferingRepository offerings,
                             UserRepository users, StockService stock, BranchContext branches, AuditService audit) {
        this.adjustments = adjustments;
        this.storages = storages;
        this.offerings = offerings;
        this.users = users;
        this.stock = stock;
        this.branches = branches;
        this.audit = audit;
    }

    @Transactional
    public AdjustmentDto create(AdjustmentRequest req, AuthUser user) {
        Storage storage = storages.findWithBranchById(req.storageId()).orElseThrow(() -> ApiException.notFound("Storage"));
        branches.requireBranch(user, storage.getBranch().getId());
        if (!storage.isActive()) {
            throw ApiException.badRequest(storage.getName() + " is not active");
        }
        boolean count = req.kind() == AdjustmentKind.COUNT;
        Set<Long> seen = new HashSet<>();
        for (LineRequest l : req.lines()) {
            if (!seen.add(l.offeringId())) {
                throw ApiException.badRequest("Each entry can only appear once in an adjustment");
            }
            if (count && l.operation() != Operation.SET) {
                throw ApiException.badRequest("A stock count sets each counted quantity");
            }
            if (!count && (l.reason() == null || l.reason() == AdjustmentReason.COUNT)) {
                throw ApiException.badRequest("Choose a reason for every line");
            }
            if (!count && l.reason() == AdjustmentReason.OTHER && blank(l.note()) && blank(req.note())) {
                throw ApiException.badRequest("Add a note explaining \"Other\"");
            }
        }

        StockAdjustment a = new StockAdjustment();
        a.setStorage(storage);
        a.setKind(req.kind());
        a.setNote(blank(req.note()) ? null : req.note().trim());
        a.setCreatedBy(users.getReferenceById(user.id()));
        adjustments.saveAndFlush(a);
        a.setAdjustmentNumber((count ? "CNT-" : "ADJ-") + String.format("%05d", a.getId()));

        BigDecimal total = BigDecimal.ZERO;
        // Entry id order, so concurrent adjustments lock stock rows in the same order.
        List<LineRequest> ordered = new ArrayList<>(req.lines());
        ordered.sort(Comparator.comparing(LineRequest::offeringId));
        for (LineRequest l : ordered) {
            Offering o = offerings.findWithComponentsById(l.offeringId()).orElseThrow(() -> ApiException.notFound("Catalog entry"));
            if (!o.getType().tracksStock()) {
                throw ApiException.badRequest(o.getName() + " has no stock of its own");
            }
            AdjustmentReason reason = count ? AdjustmentReason.COUNT : l.reason();
            String note = blank(l.note()) ? null : l.note().trim();
            Ref ref = new Ref(user.id(), null, null, note != null ? note : a.getNote(), a.getId(), reason);
            int amount = UnitConversion.toBase(o, l.quantities());
            int change;
            int after;
            switch (l.operation()) {
                case INCREASE -> {
                    requirePositive(amount, o);
                    after = stock.add(storage.getId(), o.getId(), amount, MovementType.ADJUSTMENT_IN, ref);
                    change = amount;
                }
                case DECREASE -> {
                    requirePositive(amount, o);
                    after = stock.take(storage.getId(), o.getId(), amount, MovementType.ADJUSTMENT_OUT, ref);
                    change = -amount;
                }
                default -> {
                    change = stock.setAndReturnChange(storage.getId(), o.getId(), amount, ref, count ? MovementType.COUNT : null);
                    after = amount;
                }
            }
            if (!reason.fits(change)) {
                throw ApiException.badRequest("\"" + reason.label() + "\" doesn't fit a stock " + (change > 0 ? "increase" : "decrease")
                        + " for " + o.getName());
            }
            StockAdjustmentLine line = new StockAdjustmentLine();
            line.setAdjustment(a);
            line.setOffering(o);
            line.setName(o.getName());
            line.setSku(o.getSku());
            line.setUnit(o.getUnitSymbol());
            line.setReason(reason);
            line.setNote(note);
            line.setBeforeQty(after - change);
            line.setChangeQty(change);
            line.setAfterQty(after);
            line.setExpectedQty(count ? l.expected() : null);
            line.setUnitCost(o.getCost());
            line.setValueChange(Money.round(o.getCost().multiply(BigDecimal.valueOf(change))));
            a.getLines().add(line);
            total = total.add(line.getValueChange());
        }
        a.setLineCount(a.getLines().size());
        a.setValueChange(total);
        adjustments.flush();

        long changed = a.getLines().stream().filter(l -> l.getChangeQty() != 0).count();
        audit.log(count ? AuditAction.STOCK_COUNTED : AuditAction.STOCK_ADJUSTED, "StockAdjustment", a.getId(),
                a.getAdjustmentNumber() + " at " + storage.getBranch().getName() + " / " + storage.getName() + ": "
                        + a.getLineCount() + (a.getLineCount() == 1 ? " line" : " lines") + ", " + changed + " changed, value "
                        + total.toPlainString() + describe(a));
        return AdjustmentDto.from(a, true);
    }

    @Transactional(readOnly = true)
    public Page<AdjustmentDto> search(Long branchId, Long storageId, AdjustmentKind kind, AdjustmentReason reason, Instant from,
                                      Instant to, Pageable pageable) {
        return adjustments.search(branchId, storageId, kind, reason, from, to, pageable).map(a -> AdjustmentDto.from(a, false));
    }

    @Transactional(readOnly = true)
    public AdjustmentDto get(Long id, AuthUser user) {
        StockAdjustment a = adjustments.findById(id).orElseThrow(() -> ApiException.notFound("Adjustment"));
        branches.requireBranch(user, a.getStorage().getBranch().getId());
        return AdjustmentDto.from(a, true);
    }

    /** Products and items with their quantity in one storage, for counting. */
    @Transactional(readOnly = true)
    public List<SheetRow> countSheet(Long storageId, Long categoryId, OfferingType type, AuthUser user) {
        Storage storage = storages.findWithBranchById(storageId).orElseThrow(() -> ApiException.notFound("Storage"));
        branches.requireBranch(user, storage.getBranch().getId());
        Set<OfferingType> types = type == null ? Set.of(OfferingType.PRODUCT, OfferingType.ITEM) : Set.of(type);
        if (types.stream().anyMatch(t -> !t.tracksStock())) {
            throw ApiException.badRequest("Only products and items can be counted");
        }
        var page = offerings.search(null, types, categoryId, true, false, false, List.of(storageId), true, List.of(0L),
                PageRequest.of(0, 2000, Sort.by("name")));
        Map<Long, Integer> qty = stock.totals(page.getContent().stream().map(Offering::getId).toList(), List.of(storageId));
        return page.getContent().stream()
                .map(o -> new SheetRow(o.getId(), o.getType(), o.getName(), o.getNameKm(), o.getSku(), o.getUnitSymbol(),
                        o.getCategory() == null ? null : o.getCategory().getName(), qty.getOrDefault(o.getId(), 0), o.getCost()))
                .toList();
    }

    private static String describe(StockAdjustment a) {
        String lines = a.getLines().stream().filter(l -> l.getChangeQty() != 0).limit(5)
                .map(l -> (l.getChangeQty() > 0 ? "+" : "") + l.getChangeQty() + " " + l.getName() + " (" + l.getReason().label() + ")")
                .reduce((x, y) -> x + "; " + y).orElse("");
        return lines.isEmpty() ? "" : ": " + lines + (a.getLines().stream().filter(l -> l.getChangeQty() != 0).count() > 5 ? "; ..." : "");
    }

    private static void requirePositive(int amount, Offering o) {
        if (amount <= 0) {
            throw ApiException.badRequest("Enter an amount above zero for " + o.getName());
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
