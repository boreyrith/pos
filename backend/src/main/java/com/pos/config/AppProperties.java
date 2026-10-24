package com.pos.config;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
/** @param secretsKey base64 AES-256 key for secrets stored in the database (see SecretBox) */
public record AppProperties(Jwt jwt, Store store, Khqr khqr, String secretsKey, Holds holds) {

    /** @param expiry how long a held sale waits before it expires; @param lockTimeout when an abandoned "in use" lock is released */
    public record Holds(Duration expiry, Duration lockTimeout) {
    }

    /**
     * @param refreshReuseGrace how long after a refresh token is replaced a repeat of it still
     *                          counts as a lost response rather than theft (0 turns the grace off)
     */
    public record Jwt(String secret, Duration accessTokenTtl, Duration refreshTokenTtl, boolean refreshCookieSecure,
                      Duration refreshReuseGrace) {
    }

    /**
     * Defaults for the first start only: after that, name and time zone are edited in the app
     * (Settings > Company). The base currency and rates live in the database too.
     */
    public record Store(String name, ZoneId timezone) {
    }

    public enum KhqrMode { SIMULATED, BAKONG }

    /**
     * Server defaults. The account, merchant name and city fill the settings on first start; mode,
     * API URL, token and expiry apply until an admin saves Settings > KHQR payments.
     */
    public record Khqr(KhqrMode mode, String bakongAccountId, String merchantName, String merchantCity,
                       String apiBaseUrl, String apiToken, Duration expiry) {
    }
}
