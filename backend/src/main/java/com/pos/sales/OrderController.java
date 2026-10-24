package com.pos.sales;

import com.pos.sales.SalesDtos.ReturnRequest;
import java.time.Instant;
import java.time.LocalDate;

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
import com.pos.common.PageResponse;
import com.pos.settings.SettingsService;
import com.pos.inventory.BranchContext;
import com.pos.sales.SalesDtos.CheckoutRequest;
import com.pos.sales.SalesDtos.OrderDto;
import com.pos.sales.SalesDtos.OrderSummaryDto;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService service;
    private final SettingsService settings;
    private final BranchContext branches;

    public OrderController(OrderService service, SettingsService settings, BranchContext branches) {
        this.service = service;
        this.settings = settings;
        this.branches = branches;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SALES_SELL')")
    public OrderDto checkout(@Valid @RequestBody CheckoutRequest req, @AuthenticationPrincipal AuthUser cashier) {
        return service.checkout(req, cashier);
    }

    /**
     * {@code from} and {@code to} are inclusive calendar days in the store's time zone. Staff see
     * their own branch; admins pick one with {@code branchId} or see all.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('ORDERS_VIEW')")
    public PageResponse<OrderSummaryDto> search(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @AuthenticationPrincipal AuthUser user) {
        Instant start = from == null ? null : from.atStartOfDay(settings.current().timezone()).toInstant();
        Instant end = to == null ? null : to.plusDays(1).atStartOfDay(settings.current().timezone()).toInstant();
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by(Sort.Direction.DESC, "id"));
        return PageResponse.of(service.search(branches.viewScope(user, branchId), status, start, end, q, customerId, pageable),
                o -> o);
    }

    /** Every return and refund, newest first; same branch rules as orders. */
    @GetMapping("/returns")
    @PreAuthorize("hasAuthority('ORDERS_VIEW')")
    public PageResponse<SalesDtos.ReturnSummaryDto> returns(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @AuthenticationPrincipal AuthUser user) {
        Instant start = from == null ? null : from.atStartOfDay(settings.current().timezone()).toInstant();
        Instant end = to == null ? null : to.plusDays(1).atStartOfDay(settings.current().timezone()).toInstant();
        return PageResponse.of(service.returns(branches.viewScope(user, branchId), start, end, customerId, q,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100))), r -> r);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ORDERS_VIEW','SALES_SELL')")
    public OrderDto get(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return service.get(id, user);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('ORDERS_CANCEL','SALES_SELL')")
    public OrderDto cancel(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return service.cancel(id, user);
    }

    /** Refunds everything not yet returned. */
    @PostMapping("/{id}/refund")
    @PreAuthorize("hasAuthority('ORDERS_REFUND')")
    public OrderDto refund(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return service.refund(id, user);
    }

    /** Takes back some units of some lines and refunds what was paid for them, tax included. */
    @PostMapping("/{id}/returns")
    @PreAuthorize("hasAuthority('ORDERS_REFUND')")
    public OrderDto returnItems(@PathVariable Long id, @Valid @RequestBody ReturnRequest req,
                                @AuthenticationPrincipal AuthUser user) {
        return service.returnItems(id, req, user);
    }
}
