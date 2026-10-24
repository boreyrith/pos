package com.pos.payment;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pos.sales.OrderService;

/** Releases stock held by KHQR orders that were never paid, even if no screen is polling them. */
@Component
public class PaymentExpiryJob {

    private final OrderService orders;

    public PaymentExpiryJob(OrderService orders) {
        this.orders = orders;
    }

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT30S")
    public void run() {
        orders.expireOverduePayments();
    }
}
