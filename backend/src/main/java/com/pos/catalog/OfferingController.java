package com.pos.catalog;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.pos.catalog.CatalogDtos.OfferingDto;
import com.pos.catalog.CatalogDtos.OfferingRequest;
import com.pos.auth.AuthUser;
import com.pos.catalog.CatalogDtos.StockChangeRequest;
import com.pos.catalog.CatalogDtos.StockLevelDto;
import com.pos.inventory.BranchContext;
import com.pos.common.ApiException;
import com.pos.common.PageResponse;

import jakarta.validation.Valid;

/** The catalog of products, services and items. */
@RestController
@RequestMapping("/offerings")
public class OfferingController {

    private final OfferingService service;
    private final BranchContext branches;

    private final QuickPicksService quickPicks;

    public OfferingController(OfferingService service, BranchContext branches, QuickPicksService quickPicks) {
        this.quickPicks = quickPicks;
        this.service = service;
        this.branches = branches;
    }

    /**
     * Which storages' stock to show: one storage ({@code storageId}), every storage of a branch
     * ({@code branchId}), or by default the current branch's selling storage for the sell screen
     * and all of the current branch's storages otherwise. Viewing other branches is allowed.
     *
     * @param type    one or more types, e.g. {@code type=PRODUCT&type=ITEM}; all types when omitted
     * @param forSale only what can go in a cart (the sell screen uses this)
     */
    @GetMapping
    public PageResponse<OfferingDto> search(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) Set<OfferingType> type,
                                            @RequestParam(required = false) Long categoryId,
                                            @RequestParam(defaultValue = "true") boolean activeOnly,
                                            @RequestParam(defaultValue = "false") boolean forSale,
                                            @RequestParam(defaultValue = "false") boolean lowStock,
                                            @RequestParam(required = false) Long storageId,
                                            @RequestParam(required = false) Long branchId,
                                            /** Only these entries (e.g. to refresh a held cart's prices and stock). */
                                            @RequestParam(required = false) List<Long> ids,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size,
                                            @AuthenticationPrincipal AuthUser user) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), Sort.by("name"));
        var scope = scope(storageId, branchId, forSale, user);
        return PageResponse.of(service.search(q, type, categoryId, activeOnly, forSale, lowStock, scope, ids, pageable),
                o -> o);
    }

    @GetMapping("/{id}")
    public OfferingDto get(@PathVariable Long id, @RequestParam(required = false) Long storageId,
                           @RequestParam(required = false) Long branchId, @AuthenticationPrincipal AuthUser user) {
        return service.get(id, scope(storageId, branchId, false, user));
    }

    /** Stock in every storage of every branch. */
    @GetMapping("/{id}/stock-levels")
    public List<StockLevelDto> levels(@PathVariable Long id) {
        return service.levels(id);
    }

    // ---- Quick picks for the sell screen (declared before /{id} so the paths are not taken as ids)

    /** Your favourites, in the order you added them; only what can be sold now. */
    @GetMapping("/favourites")
    @PreAuthorize("hasAuthority('SALES_SELL')")
    public List<OfferingDto> favourites(@AuthenticationPrincipal AuthUser user) {
        return quickPicks.favourites(user, scope(null, null, true, user));
    }

    /** The ids of your favourites, to mark the stars on every tile. */
    @GetMapping("/favourites/ids")
    @PreAuthorize("hasAuthority('SALES_SELL')")
    public List<Long> favouriteIds(@AuthenticationPrincipal AuthUser user) {
        return quickPicks.favouriteIds(user);
    }

    @PutMapping("/{id}/favourite")
    @PreAuthorize("hasAuthority('SALES_SELL')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addFavourite(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        quickPicks.addFavourite(id, user);
    }

    @DeleteMapping("/{id}/favourite")
    @PreAuthorize("hasAuthority('SALES_SELL')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFavourite(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        quickPicks.removeFavourite(id, user);
    }

    /** This branch's best sellers over the last {@code days}, by units kept (sold less returned). */
    @GetMapping("/top-sellers")
    @PreAuthorize("hasAuthority('SALES_SELL')")
    public List<QuickPicksService.TopSeller> topSellers(@RequestParam(defaultValue = "30") int days,
                                                       @RequestParam(defaultValue = "24") int limit,
                                                       @AuthenticationPrincipal AuthUser user) {
        return quickPicks.topSellers(user, Math.clamp(days, 1, 365), Math.clamp(limit, 1, 60), scope(null, null, true, user));
    }

    /** Unused in-store barcodes (EAN-13, prefix 200) for new or extra codes. */
    @GetMapping("/barcodes/next")
    @PreAuthorize("hasAuthority('CATALOG_MANAGE')")
    public List<String> nextBarcodes(@RequestParam(defaultValue = "1") int count) {
        return service.nextBarcodes(Math.clamp(count, 1, 20));
    }

    @GetMapping("/barcode/{barcode}")
    public OfferingDto byBarcode(@PathVariable String barcode, @AuthenticationPrincipal AuthUser user) {
        return service.forSaleByBarcode(barcode, scope(null, null, true, user));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CATALOG_MANAGE')")
    public OfferingDto create(@Valid @RequestBody OfferingRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.create(req, user);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CATALOG_MANAGE')")
    public OfferingDto update(@PathVariable Long id, @Valid @RequestBody OfferingRequest req,
                              @AuthenticationPrincipal AuthUser user) {
        return service.update(id, req, scope(null, null, false, user));
    }

    /** Admins in any branch; managers in their own branch's storages. */
    @PostMapping("/{id}/stock")
    @PreAuthorize("hasAuthority('STOCK_ADJUST')")
    public OfferingDto changeStock(@PathVariable Long id, @Valid @RequestBody StockChangeRequest req,
                                   @AuthenticationPrincipal AuthUser user) {
        return service.changeStock(id, req, user);
    }

    /** PNG, JPEG or WebP up to 2 MB, as multipart field "file". */
    @PutMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('CATALOG_MANAGE')")
    public OfferingDto uploadImage(@PathVariable Long id, @RequestParam("file") MultipartFile file,
                                   @AuthenticationPrincipal AuthUser user) throws IOException {
        return service.setImage(id, file.getBytes(), scope(null, null, false, user));
    }

    @DeleteMapping("/{id}/image")
    @PreAuthorize("hasAuthority('CATALOG_MANAGE')")
    public OfferingDto removeImage(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return service.removeImage(id, scope(null, null, false, user));
    }

    /**
     * Public, so img tags can load it without a token; the URL carries ?v=imageVersion, so it can be
     * cached for a long time and still change when the image does.
     */
    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long id) {
        OfferingImage image = service.image(id).orElseThrow(() -> ApiException.notFound("Image"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(image.getData());
    }

    private List<Long> scope(Long storageId, Long branchId, boolean forSale, AuthUser user) {
        if (storageId != null) {
            return List.of(storageId);
        }
        if (branchId != null) {
            return branches.storageIds(branchId);
        }
        if (forSale) {
            return List.of(branches.sellingStorage(user).getId());
        }
        return branches.storageIds(branches.currentBranchId(user));
    }
}
