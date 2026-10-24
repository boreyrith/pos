package com.pos.settings;

import java.time.Duration;

import com.pos.config.AppProperties.KhqrMode;

/**
 * The KHQR settings in effect: what an admin saved under Settings > KHQR payments, or the server
 * default (app.khqr.*) for anything not saved. {@code apiToken} is decrypted; never send it out.
 */
public record KhqrConfig(KhqrMode mode, String accountId, String merchantName, String merchantCity, String apiBaseUrl,
                         String apiToken, Duration expiry, boolean modeSaved, boolean apiBaseUrlSaved, TokenSource tokenSource,
                         boolean expirySaved) {

    /** Where the token comes from: saved in the app, the server's BAKONG_TOKEN, or nowhere. */
    public enum TokenSource { SAVED, SERVER, NONE }

    public boolean simulated() {
        return mode == KhqrMode.SIMULATED;
    }

    public boolean hasToken() {
        return apiToken != null && !apiToken.isBlank();
    }
}
