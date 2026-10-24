package com.pos.purchasing;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.AuthUser;
import com.pos.common.PageResponse;
import com.pos.purchasing.PurchasingDtos.InvoiceDto;
import com.pos.purchasing.PurchasingDtos.InvoiceRequest;
import com.pos.purchasing.PurchasingDtos.OrderDto;
import com.pos.purchasing.PurchasingDtos.OrderRequest;
import com.pos.purchasing.PurchasingDtos.OrderSummaryDto;
import com.pos.purchasing.PurchasingDtos.PayablesSummary;
import com.pos.purchasing.PurchasingDtos.PaymentRequest;
import com.pos.purchasing.PurchasingDtos.ReceiveRequest;
import com.pos.purchasing.PurchasingDtos.VendorDto;
import com.pos.purchasing.PurchasingDtos.VendorRequest;

import jakarta.validation.Valid;

/** Purchasing: /vendors, /purchase-orders and /vendor-invoices. Each action needs its own permission. */
@RestController
public class PurchasingController {

    private final PurchasingService service;

    public PurchasingController(PurchasingService service) {
        this.service = service;
    }

    // vendors

    @GetMapping("/vendors")
    @PreAuthorize("hasAuthority('PURCHASE_VIEW')")
    public List<VendorDto> vendors() {
        return service.vendors();
    }

    @PostMapping("/vendors")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('VENDORS_MANAGE')")
    public VendorDto createVendor(@Valid @RequestBody VendorRequest req) {
        return service.createVendor(req);
    }

    @PutMapping("/vendors/{id}")
    @PreAuthorize("hasAuthority('VENDORS_MANAGE')")
    public VendorDto updateVendor(@PathVariable Long id, @Valid @RequestBody VendorRequest req) {
        return service.updateVendor(id, req);
    }

    // purchase orders

    /** Staff see their own branch's orders; people with "All branches" pick one or see all. */
    @GetMapping("/purchase-orders")
    @PreAuthorize("hasAuthority('PURCHASE_VIEW')")
    public PageResponse<OrderSummaryDto> orders(@RequestParam(required = false) Long branchId,
                                                @RequestParam(required = false) PurchaseStatus status,
                                                @RequestParam(required = false) Long vendorId,
                                                @RequestParam(required = false) String q,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "25") int size,
                                                @AuthenticationPrincipal AuthUser user) {
        return PageResponse.of(service.search(user, branchId, status, vendorId, q, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100))),
                o -> o);
    }

    @GetMapping("/purchase-orders/{id}")
    @PreAuthorize("hasAuthority('PURCHASE_VIEW')")
    public OrderDto order(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return service.get(id, user);
    }

    @PostMapping("/purchase-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PURCHASE_ORDER')")
    public OrderDto create(@Valid @RequestBody OrderRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.create(req, user);
    }

    @PutMapping("/purchase-orders/{id}")
    @PreAuthorize("hasAuthority('PURCHASE_ORDER')")
    public OrderDto update(@PathVariable Long id, @Valid @RequestBody OrderRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.update(id, req, user);
    }

    @PostMapping("/purchase-orders/{id}/place")
    @PreAuthorize("hasAuthority('PURCHASE_ORDER')")
    public OrderDto place(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return service.place(id, user);
    }

    @PostMapping("/purchase-orders/{id}/cancel")
    @PreAuthorize("hasAuthority('PURCHASE_ORDER')")
    public OrderDto cancel(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return service.cancel(id, user);
    }

    @PostMapping("/purchase-orders/{id}/close")
    @PreAuthorize("hasAuthority('PURCHASE_ORDER')")
    public OrderDto close(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return service.close(id, user);
    }

    @PostMapping("/purchase-orders/{id}/receive")
    @PreAuthorize("hasAuthority('PURCHASE_RECEIVE')")
    public OrderDto receive(@PathVariable Long id, @Valid @RequestBody ReceiveRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.receive(id, req, user);
    }

    // invoices and payments

    @GetMapping("/vendor-invoices")
    @PreAuthorize("hasAuthority('PURCHASE_VIEW')")
    public PageResponse<InvoiceDto> invoices(@RequestParam(required = false) Long vendorId,
                                             @RequestParam(required = false) InvoiceStatus status,
                                             @RequestParam(defaultValue = "false") boolean overdue,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "25") int size) {
        return PageResponse.of(service.invoices(vendorId, status, overdue, q, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100))), i -> i);
    }

    @GetMapping("/vendor-invoices/summary")
    @PreAuthorize("hasAuthority('PURCHASE_VIEW')")
    public PayablesSummary summary(@AuthenticationPrincipal AuthUser user) {
        return service.summary(user);
    }

    @GetMapping("/vendor-invoices/{id}")
    @PreAuthorize("hasAuthority('PURCHASE_VIEW')")
    public InvoiceDto invoice(@PathVariable Long id) {
        return service.invoice(id);
    }

    @PostMapping("/vendor-invoices")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PURCHASE_INVOICE')")
    public InvoiceDto recordInvoice(@Valid @RequestBody InvoiceRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.recordInvoice(req, user);
    }

    @PostMapping("/vendor-invoices/{id}/void")
    @PreAuthorize("hasAuthority('PURCHASE_INVOICE')")
    public InvoiceDto voidInvoice(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return service.voidInvoice(id, user);
    }

    @PostMapping("/vendor-invoices/{id}/payments")
    @PreAuthorize("hasAuthority('PURCHASE_PAY')")
    public InvoiceDto pay(@PathVariable Long id, @Valid @RequestBody PaymentRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.pay(id, req, user);
    }
}
