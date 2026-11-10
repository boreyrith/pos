package com.pos.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Slows down guessing from one place. Account lockout (LoginAttempts) stops many guesses at one
 * account; this stops one place trying a common password across many accounts, or unknown names.
 * After {@link #MAX_FAILURES} failed sign-ins from an address within {@link #WINDOW}, more are refused
 * until the oldest falls out of the window. In memory: one backend instance. The address is the one
 * the proxy in front reports, so this protects deployments behind nginx (deploy/).
 */
@Component
public class SignInThrottle {

    static final int MAX_FAILURES = 20;
    static final Duration WINDOW = Duration.ofMinutes(10);

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    /** Seconds until another try is allowed, or 0 when it is allowed now. */
    public long retryAfter(String ip, Instant now) {
        if (ip == null) {
            return 0;
        }
        Deque<Instant> recent = failures.get(ip);
        if (recent == null) {
            return 0;
        }
        synchronized (recent) {
            prune(recent, now);
            if (recent.size() < MAX_FAILURES) {
                return 0;
            }
            return Math.max(1, Duration.between(now, recent.peekFirst().plus(WINDOW)).toSeconds());
        }
    }

    public void failed(String ip, Instant now) {
        if (ip == null) {
            return;
        }
        Deque<Instant> recent = failures.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (recent) {
            prune(recent, now);
            recent.addLast(now);
        }
        // Don't let addresses that stopped trying pile up.
        if (failures.size() > 10_000) {
            failures.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    prune(e.getValue(), now);
                    return e.getValue().isEmpty();
                }
            });
        }
    }

    private static void prune(Deque<Instant> recent, Instant now) {
        while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(WINDOW))) {
            recent.pollFirst();
        }
    }
}
