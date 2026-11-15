package com.pos.shop;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pos.common.ApiException;

/**
 * Limits what one address can do in the open shop API, per kind of request: each unpaid order holds stock
 * until its KHQR code expires; each sign-up makes a customer record; each promotion preview runs the
 * promotion engine; each payment check may ask Bakong. Kept in memory (one API instance).
 */
@Component
class ShopThrottle {

    /** A kind of request and how many one address may make in its window. */
    enum Limit {
        ORDER(10, Duration.ofMinutes(15), "Too many orders from this device. Try again in a few minutes."),
        SIGN_UP(5, Duration.ofHours(1), "Too many new accounts from this device. Try again later."),
        PROMOTIONS(120, Duration.ofMinutes(1), "Too many requests from this device. Wait a moment and try again."),
        PAYMENT_CHECK(30, Duration.ofMinutes(1), "Too many requests from this device. Wait a moment and try again.");

        final int max;
        final Duration window;
        final String message;

        Limit(int max, Duration window, String message) {
            this.max = max;
            this.window = window;
            this.message = message;
        }
    }

    private final Map<String, Deque<Instant>> seen = new ConcurrentHashMap<>();

    /** Counts one request; refused (429) when the address has used up its limit. */
    void check(Limit limit, String ip, Instant now) {
        if (ip == null) {
            return;
        }
        Deque<Instant> times = seen.computeIfAbsent(limit + "|" + ip, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(limit.window))) {
                times.pollFirst();
            }
            if (times.size() >= limit.max) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, limit.message, "TOO_MANY_ATTEMPTS");
            }
            times.addLast(now);
        }
    }

    /** Forgets addresses that have been quiet for longer than any window, so memory doesn't grow. */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT10M")
    void forgetQuiet() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        for (Iterator<Deque<Instant>> it = seen.values().iterator(); it.hasNext(); ) {
            Deque<Instant> times = it.next();
            synchronized (times) {
                if (times.isEmpty() || times.peekLast().isBefore(cutoff)) {
                    it.remove();
                }
            }
        }
    }
}
