package com.pos.payment.khqr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Builds a dynamic KHQR string (EMVCo merchant-presented QR with the NBC Bakong profile) for an
 * individual Bakong account, and its MD5 hash, which Bakong uses to look the payment up.
 */
public final class KhqrPayload {

    public enum Currency {
        USD("840"), KHR("116");

        final String numeric;

        Currency(String numeric) {
            this.numeric = numeric;
        }
    }

    public record Request(String bakongAccountId, String merchantName, String merchantCity, BigDecimal amount,
                          Currency currency, String billNumber, Instant createdAt, Instant expiresAt) {
    }

    public record Result(String payload, String md5) {
    }

    private KhqrPayload() {
    }

    public static Result build(Request r) {
        StringBuilder sb = new StringBuilder();
        sb.append(tlv("00", "01"));                                 // payload format indicator
        sb.append(tlv("01", "12"));                                 // dynamic QR (single use, has amount)
        sb.append(tlv("29", tlv("00", r.bakongAccountId())));       // individual account information
        sb.append(tlv("52", "5999"));                               // merchant category code
        sb.append(tlv("53", r.currency().numeric));
        sb.append(tlv("54", formatAmount(r.amount(), r.currency())));
        sb.append(tlv("58", "KH"));
        sb.append(tlv("59", truncate(r.merchantName(), 25)));
        sb.append(tlv("60", truncate(r.merchantCity(), 15)));
        if (r.billNumber() != null && !r.billNumber().isBlank()) {
            sb.append(tlv("62", tlv("01", truncate(r.billNumber(), 25))));
        }
        sb.append(tlv("99", tlv("00", String.valueOf(r.createdAt().toEpochMilli()))
                + tlv("01", String.valueOf(r.expiresAt().toEpochMilli()))));
        sb.append("6304");
        String payload = sb + crc16(sb.toString());
        return new Result(payload, md5(payload));
    }

    static String formatAmount(BigDecimal amount, Currency currency) {
        BigDecimal scaled = currency == Currency.KHR
                ? amount.setScale(0, RoundingMode.HALF_UP)
                : amount.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return scaled.toPlainString();
    }

    static String tlv(String tag, String value) {
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        if (length > 99) {
            throw new IllegalArgumentException("KHQR field " + tag + " is too long");
        }
        return tag + String.format("%02d", length) + value;
    }

    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF), as EMVCo requires. */
    static String crc16(String data) {
        int crc = 0xFFFF;
        for (byte b : data.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            }
        }
        return String.format("%04X", crc & 0xFFFF);
    }

    private static String truncate(String s, int max) {
        String trimmed = s == null ? "" : s.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static String md5(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
