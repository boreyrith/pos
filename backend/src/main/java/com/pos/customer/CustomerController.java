package com.pos.customer;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.AuthUser;
import com.pos.common.PageResponse;
import com.pos.customer.CustomerService.CustomerDetail;
import com.pos.customer.CustomerService.CustomerDto;
import com.pos.customer.CustomerService.CustomerRequest;

import jakarta.validation.Valid;

/** Customers. Cashiers can look them up to sell to (with "Sell"); the list and details need "View customers". */
@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    /** Search by name, code, email or phone (any formatting); the Walk-in Customer comes first. */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('CUSTOMERS_VIEW','SALES_SELL')")
    public PageResponse<CustomerDto> search(@RequestParam(required = false) String q,
                                            @RequestParam(defaultValue = "false") boolean activeOnly,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "25") int size) {
        return PageResponse.of(service.search(q, activeOnly, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100))), c -> c);
    }

    @GetMapping("/walk-in")
    @PreAuthorize("hasAnyAuthority('CUSTOMERS_VIEW','SALES_SELL')")
    public CustomerDto walkIn() {
        return CustomerDto.from(service.walkIn());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CUSTOMERS_VIEW')")
    public CustomerDetail get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CUSTOMERS_MANAGE')")
    public CustomerDto create(@Valid @RequestBody CustomerRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.create(req, user);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CUSTOMERS_MANAGE')")
    public CustomerDto update(@PathVariable Long id, @Valid @RequestBody CustomerRequest req) {
        return service.update(id, req);
    }
}
