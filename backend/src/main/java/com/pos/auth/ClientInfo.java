package com.pos.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Where a request came from, for the list of signed-in devices. Informational only: the address is
 * the one the proxy in front reports (X-Real-IP, set by nginx from the connection), else the last
 * X-Forwarded-For hop, else the connection itself.
 */
public record ClientInfo(String ip, String userAgent) {

    public static ClientInfo from(HttpServletRequest request) {
        String ip = request.getHeader("X-Real-IP");
        if (ip == null || ip.isBlank()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] hops = forwarded.split(",");
                ip = hops[hops.length - 1].trim();
            }
        }
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        String agent = request.getHeader("User-Agent");
        return new ClientInfo(cut(ip, 45), cut(agent, 255));
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
