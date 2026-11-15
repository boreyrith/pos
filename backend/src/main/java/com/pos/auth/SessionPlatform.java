package com.pos.auth;

/**
 * Where a session was signed in: each platform has its own sessions and refresh cookie, so signing in
 * or out of one leaves the others as they are. The device lists group sessions by it.
 */
public enum SessionPlatform {
    /** The administration web app (frontend-ctl, /ctl). */
    CTL("pos_refresh_ctl", "administration"),
    /** The till web app (frontend-cas, /cas). */
    CAS("pos_refresh_cas", "the till"),
    /** The mobile cashier app. Its cookie keeps the original name, so installed apps keep working. */
    APP("pos_refresh", "the mobile app");

    private final String cookie;
    private final String label;

    SessionPlatform(String cookie, String label) {
        this.cookie = cookie;
        this.label = label;
    }

    /** The refresh cookie's name on this platform. */
    public String cookie() {
        return cookie;
    }

    /** For audit summaries: "Signed in to administration on ...". */
    public String label() {
        return label;
    }

    /** A client that names no platform is the mobile app (versions from before platforms existed). */
    public static SessionPlatform orApp(SessionPlatform p) {
        return p == null ? APP : p;
    }
}
