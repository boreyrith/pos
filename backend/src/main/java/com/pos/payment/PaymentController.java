package com.pos.payment;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.AuthUser;
import com.pos.sales.OrderService;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final OrderService orders;

    public PaymentController(OrderService orders) {
        this.orders = orders;
    }

    /** Polled by the checkout screen while a KHQR code is shown. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('SALES_SELL','ORDERS_VIEW')")
    public PaymentDto status(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return orders.refreshPayment(id, user);
    }

    /** Development only: pretends the customer paid. Rejected unless app.khqr.mode is SIMULATED. */
    @PostMapping("/{id}/simulate-paid")
    @PreAuthorize("hasAnyAuthority('SALES_SELL','ORDERS_VIEW')")
    public PaymentDto simulatePaid(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return orders.simulatePayment(id, user);
    }
}
