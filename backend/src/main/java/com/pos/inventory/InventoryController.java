package com.pos.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.Changes;
import com.pos.auth.AuthUser;
import com.pos.common.ApiException;
import com.pos.common.PageResponse;
import com.pos.settings.SettingsService;
import com.pos.inventory.InventoryDtos.BranchDto;
import com.pos.inventory.InventoryDtos.BranchRequest;
import com.pos.inventory.InventoryDtos.MovementDto;
import com.pos.inventory.InventoryDtos.StorageDto;
import com.pos.inventory.InventoryDtos.StorageRequest;
import com.pos.inventory.InventoryDtos.TransferDto;
import com.pos.inventory.InventoryDtos.TransferRequest;

import jakarta.validation.Valid;

/** Branches and storages, stock transfers, and stock history. */
@RestController
public class InventoryController {

    private final BranchRepository branches;
    private final StorageRepository storages;
    private final StockMovementRepository movements;
    private final StockService stock;
    private final TransferService transfers;
    private final BranchContext context;
    private final SettingsService settings;
    private final AuditService audit;

    public InventoryController(BranchRepository branches, StorageRepository storages, StockMovementRepository movements,
                               StockService stock, TransferService transfers, BranchContext context,
                               SettingsService settings, AuditService audit) {
        this.audit = audit;
        this.branches = branches;
        this.storages = storages;
        this.movements = movements;
        this.stock = stock;
        this.transfers = transfers;
        this.context = context;
        this.settings = settings;
    }

    // --- Branches and storages -------------------------------------------------------------

    /** Every branch with its storages. Open to all signed-in staff (for labels and transfer targets). */
    @GetMapping("/branches")
    @Transactional(readOnly = true)
    public List<BranchDto> listBranches() {
        var all = storages.findAllByOrderByBranchNameAscSellingDescNameAsc();
        return branches.findAll(Sort.by("name")).stream()
                .map(b -> new BranchDto(b.getId(), b.getCode(), b.getName(), b.getAddress(), b.isActive(),
                        all.stream().filter(s -> s.getBranch().getId().equals(b.getId())).map(StorageDto::from).toList()))
                .toList();
    }

    /** Creates a branch with a "Shop floor" selling storage. */
    @PostMapping("/branches")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    @PreAuthorize("hasAuthority('BRANCHES_MANAGE')")
    public BranchDto createBranch(@Valid @RequestBody BranchRequest req) {
        String code = req.code().trim().toUpperCase();
        if (branches.existsByCode(code)) {
            throw ApiException.conflict("Branch code " + code + " is already used");
        }
        Branch b = branches.save(new Branch(code, req.name().trim(), blankToNull(req.address())));
        Storage floor = storages.saveAndFlush(new Storage(b, "Shop floor", true));
        audit.log(AuditAction.BRANCH_CREATED, "Branch", b.getId(), "Added branch " + b.getName() + " (" + b.getCode() + ")");
        return new BranchDto(b.getId(), b.getCode(), b.getName(), b.getAddress(), b.isActive(),
                List.of(StorageDto.from(floor)));
    }

    @PutMapping("/branches/{id}")
    @Transactional
    @PreAuthorize("hasAuthority('BRANCHES_MANAGE')")
    public BranchDto updateBranch(@PathVariable Long id, @Valid @RequestBody BranchRequest req) {
        Branch b = branches.findById(id).orElseThrow(() -> ApiException.notFound("Branch"));
        Changes changes = new Changes().add("Code", b.getCode(), req.code().trim().toUpperCase()).add("Name", b.getName(), req.name().trim())
                .add("Address", b.getAddress(), blankToNull(req.address()))
                .add("Active", b.isActive() ? "yes" : "no", req.active() == null ? (b.isActive() ? "yes" : "no") : req.active() ? "yes" : "no");
        if (!changes.isEmpty()) {
            audit.log(AuditAction.BRANCH_UPDATED, "Branch", b.getId(), "Branch " + b.getName() + ": " + changes.describe(), changes);
        }
        b.setCode(req.code().trim().toUpperCase());
        b.setName(req.name().trim());
        b.setAddress(blankToNull(req.address()));
        if (req.active() != null) {
            b.setActive(req.active());
        }
        branches.flush();
        return new BranchDto(b.getId(), b.getCode(), b.getName(), b.getAddress(), b.isActive(),
                storages.findByBranchId(id).stream().sorted(Comparator.comparing(Storage::getName))
                        .map(StorageDto::from).toList());
    }

    /** Admins anywhere; managers in their own branch. */
    @PostMapping("/branches/{branchId}/storages")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    @PreAuthorize("hasAuthority('STORAGES_MANAGE')")
    public StorageDto createStorage(@PathVariable Long branchId, @Valid @RequestBody StorageRequest req,
                                    @AuthenticationPrincipal AuthUser user) {
        context.requireBranch(user, branchId);
        Branch b = branches.findById(branchId).orElseThrow(() -> ApiException.notFound("Branch"));
        boolean selling = Boolean.TRUE.equals(req.selling());
        if (selling) {
            clearSelling(branchId);
        }
        Storage created = storages.saveAndFlush(new Storage(b, req.name().trim(), selling));
        audit.log(AuditAction.STORAGE_CREATED, "Storage", created.getId(), "Added storage " + b.getName() + " / " + created.getName()
                + (selling ? " (selling)" : ""));
        return StorageDto.from(created);
    }

    /**
     * Rename, make the selling storage, or deactivate. A branch always keeps one active selling
     * storage, and a storage can only be deactivated when it is empty.
     */
    @PutMapping("/storages/{id}")
    @Transactional
    @PreAuthorize("hasAuthority('STORAGES_MANAGE')")
    public StorageDto updateStorage(@PathVariable Long id, @Valid @RequestBody StorageRequest req,
                                    @AuthenticationPrincipal AuthUser user) {
        Storage s = storages.findWithBranchById(id).orElseThrow(() -> ApiException.notFound("Storage"));
        context.requireBranch(user, s.getBranch().getId());
        String oldName = s.getName();
        boolean wasSelling = s.isSelling();
        boolean wasActive = s.isActive();
        s.setName(req.name().trim());
        if (Boolean.TRUE.equals(req.selling()) && !s.isSelling()) {
            clearSelling(s.getBranch().getId());
            s.setSelling(true);
        } else if (Boolean.FALSE.equals(req.selling()) && s.isSelling()) {
            throw ApiException.badRequest("Make another storage the selling storage instead");
        }
        if (req.active() != null && req.active() != s.isActive()) {
            if (!req.active()) {
                if (s.isSelling()) {
                    throw ApiException.badRequest("The selling storage can't be deactivated. Make another storage the selling storage first.");
                }
                int left = stock.storageTotal(id);
                if (left > 0) {
                    throw ApiException.badRequest("Move or remove the " + left + " units still in " + s.getName() + " first");
                }
            }
            s.setActive(req.active());
        }
        if (s.isSelling() && !s.isActive()) {
            s.setActive(true);
        }
        storages.flush();
        Changes changes = new Changes().add("Name", oldName, s.getName())
                .add("Selling", wasSelling ? "yes" : "no", s.isSelling() ? "yes" : "no")
                .add("Active", wasActive ? "yes" : "no", s.isActive() ? "yes" : "no");
        if (!changes.isEmpty()) {
            audit.log(AuditAction.STORAGE_UPDATED, "Storage", s.getId(), "Storage " + s.getBranch().getName() + " / " + s.getName()
                    + ": " + changes.describe(), changes);
        }
        return StorageDto.from(s);
    }

    private void clearSelling(Long branchId) {
        storages.findByBranchIdAndSellingTrue(branchId).ifPresent(current -> {
            current.setSelling(false);
            current.setActive(true);
            storages.flush();
        });
    }

    // --- Transfers -------------------------------------------------------------------------

    /** Managers see transfers to or from their branch; admins any branch (none = all). */
    @GetMapping("/transfers")
    @PreAuthorize("hasAuthority('INVENTORY_VIEW')")
    public PageResponse<TransferDto> listTransfers(@RequestParam(required = false) TransferStatus status,
                                                   @RequestParam(required = false) Long branchId,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "25") int size,
                                                   @AuthenticationPrincipal AuthUser user) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by(Sort.Direction.DESC, "id"));
        return PageResponse.of(transfers.search(status, context.viewScope(user, branchId), pageable), t -> t);
    }

    @GetMapping("/transfers/{id}")
    @PreAuthorize("hasAuthority('INVENTORY_VIEW')")
    public TransferDto getTransfer(@PathVariable Long id) {
        return transfers.get(id);
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('STOCK_TRANSFER')")
    public TransferDto createTransfer(@Valid @RequestBody TransferRequest req, @AuthenticationPrincipal AuthUser user) {
        return transfers.create(req, user);
    }

    @PostMapping("/transfers/{id}/receive")
    @PreAuthorize("hasAuthority('STOCK_TRANSFER')")
    public TransferDto receive(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return transfers.receive(id, user);
    }

    @PostMapping("/transfers/{id}/cancel")
    @PreAuthorize("hasAuthority('STOCK_TRANSFER')")
    public TransferDto cancel(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return transfers.cancel(id, user);
    }

    // --- Stock history ---------------------------------------------------------------------

    /** Managers see their branch; admins any branch (none = all). {@code from}/{@code to} are inclusive days. */
    @GetMapping("/stock-movements")
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('INVENTORY_VIEW')")
    public PageResponse<MovementDto> movements(
            @RequestParam(required = false) Long offeringId,
            @RequestParam(required = false) Long storageId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) MovementType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal AuthUser user) {
        Instant start = from == null ? null : from.atStartOfDay(settings.current().timezone()).toInstant();
        Instant end = to == null ? null : to.plusDays(1).atStartOfDay(settings.current().timezone()).toInstant();
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), Sort.by(Sort.Direction.DESC, "id"));
        return PageResponse.of(movements.search(offeringId, storageId, context.viewScope(user, branchId), type, start,
                end, pageable), MovementDto::from);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
