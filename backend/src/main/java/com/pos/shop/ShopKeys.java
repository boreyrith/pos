package com.pos.shop;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import com.pos.common.ApiException;
import com.pos.common.Hashes;

/** Random keys (sessions, guest order links), kept only as hashes; and phone numbers in one form. */
final class ShopKeys {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ShopKeys() {
    }

    static String newKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String raw) {
        return Hashes.sha256(raw);
    }

    /** Equal hashes without leaking, by timing, how much of them matched. */
    static boolean matches(String raw, String hash) {
        return raw != null && hash != null
                && MessageDigest.isEqual(hash(raw).getBytes(StandardCharsets.US_ASCII), hash.getBytes(StandardCharsets.US_ASCII));
    }

    /** "012 345 678", "012-345-678" and "012345678" are the same number; refused unless 8 to 15 digits (+ allowed first). */
    static String phone(String raw) {
        String p = raw == null ? "" : raw.replaceAll("[\\s\\-().]", "");
        if (!p.matches("\\+?[0-9]{8,15}")) {
            throw ApiException.badRequest("Enter a phone number of 8 to 15 digits.");
        }
        return p;
    }
}
