package com.pos.auth;

/** Turns a browser's User-Agent into "Chrome on Windows" and a device kind, for the sessions list. */
public final class DeviceNames {

    public enum Kind { DESKTOP, PHONE, TABLET }

    public record Device(String browser, String os, Kind kind) {
        public String label() {
            return browser + " on " + os;
        }
    }

    private DeviceNames() {
    }

    public static Device describe(String ua) {
        if (ua == null || ua.isBlank()) {
            return new Device("Unknown browser", "unknown device", Kind.DESKTOP);
        }
        String os;
        Kind kind = Kind.DESKTOP;
        if (ua.contains("iPad") || (ua.contains("Macintosh") && ua.contains("Mobile/"))) {
            os = "iPad";
            kind = Kind.TABLET;
        } else if (ua.contains("iPhone")) {
            os = "iPhone";
            kind = Kind.PHONE;
        } else if (ua.contains("Android")) {
            os = "Android";
            kind = ua.contains("Mobile") ? Kind.PHONE : Kind.TABLET;
        } else if (ua.contains("Windows")) {
            os = "Windows";
        } else if (ua.contains("Mac OS X") || ua.contains("Macintosh")) {
            os = "macOS";
        } else if (ua.contains("CrOS")) {
            os = "ChromeOS";
        } else if (ua.contains("Linux")) {
            os = "Linux";
        } else {
            os = "unknown device";
        }
        String browser;
        if (ua.contains("Edg/")) {
            browser = "Edge";
        } else if (ua.contains("SamsungBrowser")) {
            browser = "Samsung Internet";
        } else if (ua.contains("OPR/") || ua.contains("Opera")) {
            browser = "Opera";
        } else if (ua.contains("Firefox/") || ua.contains("FxiOS")) {
            browser = "Firefox";
        } else if (ua.contains("Chrome/") || ua.contains("CriOS")) {
            browser = "Chrome";
        } else if (ua.contains("Safari/")) {
            browser = "Safari";
        } else if (ua.contains("Dart") || ua.contains("okhttp")) {
            browser = "POS app";
        } else {
            browser = "Browser";
        }
        return new Device(browser, os, kind);
    }
}
