package com.pos.inventory;

import com.pos.common.ApiException;
import com.pos.access.Permission;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.AuthUser;
import com.pos.catalog.OfferingType;
import com.pos.common.PageResponse;
import com.pos.inventory.AdjustmentService.AdjustmentDto;
import com.pos.inventory.AdjustmentService.AdjustmentRequest;
import com.pos.inventory.AdjustmentService.SheetRow;
import com.pos.settings.SettingsService;

import jakarta.validation.Valid;

/** Stock adjustments and counts, in the person's own branch unless they work across branches. */
@RestController
@RequestMapping("/stock-adjustments")
@PreAuthorize("hasAnyAuthority('INVENTORY_VIEW','STOCK_ADJUST','STOCK_COUNT')")
public class AdjustmentController {

    /** The reasons, with whether they fit increases and/or decreases, for the screens. */
    public record ReasonDto(AdjustmentReason code, String label, boolean increase, boolean decrease) {
    }

    private final AdjustmentService service;
    private final BranchContext branches;
    private final SettingsService settings;

    public AdjustmentController(AdjustmentService service, BranchContext branches, SettingsService settings) {
        this.service = service;
        this.branches = branches;
        this.settings = settings;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdjustmentDto create(@Valid @RequestBody AdjustmentRequest req, @AuthenticationPrincipal AuthUser user) {
        // Counts and adjustments are separate permissions.
        Permission needed = req.kind() == AdjustmentKind.COUNT ? Permission.STOCK_COUNT : Permission.STOCK_ADJUST;
        if (!user.has(needed)) {
            throw new ApiException(HttpStatus.FORBIDDEN, req.kind() == AdjustmentKind.COUNT
                    ? "You don't have permission to count stock" : "You don't have permission to adjust stock");
        }
        return service.create(req, user);
    }

    @GetMapping
    public PageResponse<AdjustmentDto> search(@RequestParam(required = false) Long branchId,
                                              @RequestParam(required = false) Long storageId,
                                              @RequestParam(required = false) AdjustmentKind kind,
                                              @RequestParam(required = false) AdjustmentReason reason,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "25") int size,
                                              @AuthenticationPrincipal AuthUser user) {
        var zone = settings.current().timezone();
        Instant start = from == null ? null : from.atStartOfDay(zone).toInstant();
        Instant end = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by(Sort.Direction.DESC, "id"));
        return PageResponse.of(service.search(branches.viewScope(user, branchId), storageId, kind, reason, start, end, pageable), a -> a);
    }

    @GetMapping("/{id}")
    public AdjustmentDto get(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return service.get(id, user);
    }

    @GetMapping("/count-sheet")
    @PreAuthorize("hasAuthority('STOCK_COUNT')")
    public List<SheetRow> countSheet(@RequestParam Long storageId, @RequestParam(required = false) Long categoryId,
                                     @RequestParam(required = false) OfferingType type, @AuthenticationPrincipal AuthUser user) {
        return service.countSheet(storageId, categoryId, type, user);
    }

    @GetMapping("/reasons")
    public List<ReasonDto> reasons() {
        return java.util.Arrays.stream(AdjustmentReason.values())
                .map(r -> new ReasonDto(r, r.label(), r.fits(1), r.fits(-1)))
                .toList();
    }
}
