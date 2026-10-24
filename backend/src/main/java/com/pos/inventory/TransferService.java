package com.pos.inventory;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.auth.AuthUser;
import com.pos.auth.UserRepository;
import com.pos.catalog.Offering;
import com.pos.catalog.OfferingRepository;
import com.pos.catalog.UnitConversion;
import com.pos.common.ApiException;
import com.pos.inventory.InventoryDtos.TransferDto;
import com.pos.inventory.InventoryDtos.TransferLineRequest;
import com.pos.inventory.InventoryDtos.TransferRequest;
import com.pos.inventory.StockService.Ref;

/**
 * Moves stock between storages. Stock leaves the source when the transfer is created. Within one
 * branch it arrives at once; between branches it is in transit until the receiving branch
 * confirms, or goes back to the source if the transfer is cancelled.
 */
@Service
public class TransferService {

    private final StockTransferRepository transfers;
    private final StorageRepository storages;
    private final OfferingRepository offerings;
    private final UserRepository users;
    private final StockService stock;
    private final BranchContext branches;
    private final AuditService audit;

    public TransferService(StockTransferRepository transfers, StorageRepository storages, OfferingRepository offerings,
                           UserRepository users, StockService stock, BranchContext branches, AuditService audit) {
        this.audit = audit;
        this.transfers = transfers;
        this.storages = storages;
        this.offerings = offerings;
        this.users = users;
        this.stock = stock;
        this.branches = branches;
    }

    @Transactional(readOnly = true)
    public Page<TransferDto> search(TransferStatus status, Long branchId, Pageable pageable) {
        return transfers.search(status, branchId, pageable).map(TransferDto::from);
    }

    @Transactional(readOnly = true)
    public TransferDto get(Long id) {
        return TransferDto.from(find(id));
    }

    @Transactional
    public TransferDto create(TransferRequest req, AuthUser user) {
        Storage from = activeStorage(req.fromStorageId());
        Storage to = activeStorage(req.toStorageId());
        if (from.getId().equals(to.getId())) {
            throw ApiException.badRequest("Choose two different storages");
        }
        branches.requireBranch(user, from.getBranch().getId());

        // Merge lines for the same entry, converting each to base units.
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        Map<Long, Offering> byId = new LinkedHashMap<>();
        for (TransferLineRequest line : req.lines()) {
            Offering o = offerings.findById(line.offeringId()).orElseThrow(() -> ApiException.notFound("Catalog entry"));
            if (!o.getType().tracksStock()) {
                throw ApiException.badRequest(o.getName() + " is a service and has no stock to transfer");
            }
            int base = UnitConversion.toBase(o, line.quantities());
            if (base <= 0) {
                throw ApiException.badRequest("Enter an amount for " + o.getName());
            }
            quantities.merge(o.getId(), base, Integer::sum);
            byId.put(o.getId(), o);
        }

        StockTransfer t = new StockTransfer();
        t.setFromStorage(from);
        t.setToStorage(to);
        t.setNote(blankToNull(req.note()));
        t.setCreatedBy(users.getReferenceById(user.id()));
        t.setStatus(TransferStatus.IN_TRANSIT);
        quantities.forEach((id, qty) -> t.getLines().add(new StockTransferLine(t, byId.get(id), qty)));
        transfers.saveAndFlush(t);
        t.setTransferNumber(String.format("TR-%05d", t.getId()));

        Ref ref = new Ref(user.id(), null, t.getId(), null);
        // Sorted by entry id so concurrent transfers lock rows in the same order.
        quantities.keySet().stream().sorted()
                .forEach(id -> stock.take(from.getId(), id, quantities.get(id), MovementType.TRANSFER_OUT, ref));

        if (from.getBranch().getId().equals(to.getBranch().getId())) {
            land(t, user);
        }
        audit.log(AuditAction.TRANSFER_CREATED, "Transfer", t.getId(), t.getTransferNumber() + ": " + place(from) + " → "
                + place(to) + ", " + lines(t) + (t.getStatus() == TransferStatus.RECEIVED ? " (moved straight away)" : " (in transit)"));
        return TransferDto.from(t);
    }

    /** The receiving branch confirms the goods arrived. */
    @Transactional
    public TransferDto receive(Long id, AuthUser user) {
        StockTransfer t = lockOpen(id);
        branches.requireBranch(user, t.getToStorage().getBranch().getId());
        if (!t.getToStorage().isActive()) {
            throw ApiException.badRequest("The receiving storage is no longer active");
        }
        land(t, user);
        audit.log(AuditAction.TRANSFER_RECEIVED, "Transfer", t.getId(), "Received " + t.getTransferNumber() + " at " + place(t.getToStorage()));
        return TransferDto.from(t);
    }

    /** The sending branch calls it off; the stock goes back where it came from. */
    @Transactional
    public TransferDto cancel(Long id, AuthUser user) {
        StockTransfer t = lockOpen(id);
        branches.requireBranch(user, t.getFromStorage().getBranch().getId());
        Ref ref = new Ref(user.id(), null, t.getId(), null);
        t.getLines().stream().sorted((a, b) -> a.getOffering().getId().compareTo(b.getOffering().getId()))
                .forEach(l -> stock.add(t.getFromStorage().getId(), l.getOffering().getId(), l.getQuantity(),
                        MovementType.TRANSFER_RETURN, ref));
        t.close(TransferStatus.CANCELLED, users.getReferenceById(user.id()));
        audit.log(AuditAction.TRANSFER_CANCELLED, "Transfer", t.getId(), "Cancelled " + t.getTransferNumber()
                + "; stock returned to " + place(t.getFromStorage()));
        return TransferDto.from(t);
    }

    private void land(StockTransfer t, AuthUser user) {
        Ref ref = new Ref(user.id(), null, t.getId(), null);
        t.getLines().stream().sorted((a, b) -> a.getOffering().getId().compareTo(b.getOffering().getId()))
                .forEach(l -> stock.add(t.getToStorage().getId(), l.getOffering().getId(), l.getQuantity(),
                        MovementType.TRANSFER_IN, ref));
        t.close(TransferStatus.RECEIVED, users.getReferenceById(user.id()));
    }

    private StockTransfer lockOpen(Long id) {
        StockTransfer t = transfers.lockById(id).orElseThrow(() -> ApiException.notFound("Transfer"));
        if (t.getStatus() != TransferStatus.IN_TRANSIT) {
            throw ApiException.conflict("This transfer is already " + t.getStatus().name().toLowerCase().replace('_', ' '));
        }
        return t;
    }

    private StockTransfer find(Long id) {
        return transfers.findById(id).orElseThrow(() -> ApiException.notFound("Transfer"));
    }

    private Storage activeStorage(Long id) {
        Storage s = storages.findWithBranchById(id).orElseThrow(() -> ApiException.notFound("Storage"));
        if (!s.isActive() || !s.getBranch().isActive()) {
            throw ApiException.badRequest(s.getName() + " is not active");
        }
        return s;
    }

    private static String place(Storage s) {
        return s.getBranch().getName() + " / " + s.getName();
    }

    private static String lines(StockTransfer t) {
        return t.getLines().stream().map(l -> l.getQuantity() + (l.getUnit() == null ? "" : " " + l.getUnit()) + " " + l.getName())
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
