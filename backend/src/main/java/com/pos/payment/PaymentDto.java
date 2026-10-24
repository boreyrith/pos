package com.pos.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * {@code amount}, {@code tendered} and {@code changeDue} are in the base currency.
 * {@code currency}/{@code currencyAmount} is what a KHQR code charges; for cash, {@code tenders}
 * lists what was handed over per currency and the change is {@code changeAmount} {@code changeCurrency}.
 */
public record PaymentDto(Long id, Long orderId, PaymentMethod method, PaymentStatus status, BigDecimal amount,
                         String currency, BigDecimal currencyAmount, BigDecimal tendered, BigDecimal changeDue,
                         String changeCurrency, BigDecimal changeAmount, List<TenderDto> tenders,
                         String khqrPayload, Instant expiresAt, Instant paidAt) {

    public record TenderDto(String currency, BigDecimal amount, BigDecimal rate, BigDecimal baseAmount) {
    }

    public static PaymentDto from(Payment p) {
        return new PaymentDto(p.getId(), p.getOrder().getId(), p.getMethod(), p.getStatus(), p.getAmount(),
                p.getCurrency(), p.getCurrencyAmount(), p.getTendered(), p.getChangeDue(), p.getChangeCurrency(),
                p.getChangeAmount(),
                p.getTenders().stream().map(t -> new TenderDto(t.getCurrency(), t.getAmount(), t.getRate(),
                        t.getBaseAmount())).toList(),
                p.getStatus() == PaymentStatus.PENDING ? p.getKhqrPayload() : null,
                p.getExpiresAt(), p.getPaidAt());
    }
}
