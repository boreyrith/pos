package com.pos.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Guards the Prometheus metrics: they answer only a request with {@code Authorization: Bearer
 * <METRICS_TOKEN>}, so they stay private even where the API is reachable through the proxy. Without
 * a token configured the metrics are off (404). Staff sign-ins are not accepted here: the metrics are
 * for the monitoring server, not for people.
 */
class MetricsAccessFilter extends OncePerRequestFilter {

    static final String PATH = "/actuator/prometheus";

    private final byte[] expected;

    MetricsAccessFilter(String token) {
        this.expected = token == null || token.isBlank() ? null : ("Bearer " + token.strip()).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith(PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (expected == null) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        String given = request.getHeader(HttpHeaders.AUTHORIZATION);
        // Compared in constant time, so the response time doesn't reveal how much of the token matched.
        if (given == null || !MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8))) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            response.sendError(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        chain.doFilter(request, response);
    }
}
