package com.pos.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.pos.common.ApiException;
import com.pos.currency.Money;
import com.pos.payment.khqr.BakongClient;
import com.pos.payment.khqr.KhqrPayload;
import com.pos.settings.SettingsService;

@Service
public class KhqrService {

    private final BakongClient bakong;
    private final SettingsService settings;
    private final AskedRecently lastAsked = new AskedRecently(java.time.Duration.ofSeconds(3));

    /** Mode, expiry and account come from Settings > KHQR payments (or the server defaults). */
    public KhqrService(BakongClient bakong, SettingsService settings) {
        this.bakong = bakong;
        this.settings = settings;
    }

    /** A KHQR code for {@code amountBase}, charged in {@code currency} (USD or KHR) at its current rate. */
    public Payment createPayment(BigDecimal amountBase, Money currency, String billNumber) {
        KhqrPayload.Currency khqrCurrency;
        try {
            khqrCurrency = KhqrPayload.Currency.valueOf(currency.code());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("KHQR can only charge in USD or KHR");
        }
        BigDecimal charged = currency.fromBase(amountBase);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(settings.khqr().expiry());
        var company = settings.current();
        var result = KhqrPayload.build(new KhqrPayload.Request(company.khqrAccountId(), company.khqrMerchantName(),
                company.khqrMerchantCity(), charged, khqrCurrency, billNumber, now, expiresAt));
        return Payment.khqr(amountBase, currency.code(), charged, result.payload(), result.md5(), expiresAt);
    }

    public boolean isSimulated() {
        return settings.khqr().simulated();
    }

    /**
     * Asks Bakong whether the payment arrived, with the right amount and to our account. Returns the
     * Bakong transaction hash when paid. Always empty in simulated mode.
     */
    public Optional<String> findSettlement(Payment payment) {
        if (isSimulated() || payment.getKhqrMd5() == null) {
            return Optional.empty();
        }
        // Tills, the shop's order page and the expiry job all ask about the same payment: Bakong is asked
        // at most once per payment every few seconds (it limits how often a token may ask). Once the
        // code has expired, always ask: a payment made in its last seconds must not be missed.
        Instant now = Instant.now();
        boolean expired = payment.getExpiresAt() != null && !payment.getExpiresAt().isAfter(now);
        if (!expired && !lastAsked.askNow(payment.getId(), now)) {
            return Optional.empty();
        }
        return bakong.findByMd5(payment.getKhqrMd5())
                .filter(tx -> tx.amount().compareTo(payment.getCurrencyAmount()) == 0)
                .filter(tx -> payment.getCurrency().equalsIgnoreCase(tx.currency()))
                .filter(tx -> settings.current().khqrAccountId().equalsIgnoreCase(tx.toAccountId()))
                .map(BakongClient.Transaction::hash);
    }

    /** When each payment was last asked about, so pollers share one Bakong question per interval. */
    static final class AskedRecently {
        private final java.time.Duration every;
        private final java.util.Map<Long, Instant> asked = new java.util.concurrent.ConcurrentHashMap<>();

        AskedRecently(java.time.Duration every) {
            this.every = every;
        }

        /** True (and noted) when this payment wasn't asked about in the last interval. */
        boolean askNow(Long paymentId, Instant now) {
            if (asked.size() > 1000) {
                asked.values().removeIf(t -> t.isBefore(now.minus(java.time.Duration.ofMinutes(5))));
            }
            boolean[] ask = {false};
            asked.compute(paymentId, (id, last) -> {
                if (last == null || !last.plus(every).isAfter(now)) {
                    ask[0] = true;
                    return now;
                }
                return last;
            });
            return ask[0];
        }
    }
}
