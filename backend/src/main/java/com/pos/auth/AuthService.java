package com.pos.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.auth.AuthDtos.TokenResponse;
import com.pos.auth.AuthDtos.UserDto;
import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.AuditService.Actor;
import com.pos.common.ApiException;
import com.pos.config.AppProperties;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** A login result: the access token response plus a raw refresh token for the cookie. */
    public record Session(TokenResponse tokens, String refreshToken) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AppProperties props;
    private final AuditService audit;
    private final LoginAttempts loginAttempts;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AppProperties props, AuditService audit, LoginAttempts loginAttempts) {
        this.audit = audit;
        this.loginAttempts = loginAttempts;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.props = props;
    }

    @Transactional
    public Session login(String username, String password) {
        String name = username.trim();
        User user = users.findByUsername(name).orElse(null);
        Instant now = Instant.now();
        if (user == null) {
            audit.logAlways(new Actor(null, truncate(name, 50), null), AuditAction.LOGIN_FAILED, "User", null,
                    "Sign-in with unknown username \"" + truncate(name, 50) + "\"");
            throw wrongPassword();
        }
        Actor actor = new Actor(user.getId(), user.getUsername(), user.getBranch().getId());
        if (user.isLocked(now)) {
            audit.logAlways(actor, AuditAction.LOGIN_FAILED, "User", user.getId(), "Sign-in refused: account locked");
            long minutes = Math.max(1, Duration.between(now, user.getLockedUntil()).toMinutes() + 1);
            throw new ApiException(HttpStatus.FORBIDDEN, "Too many wrong passwords. Try again in " + minutes
                    + " minute" + (minutes == 1 ? "" : "s") + ", or ask an admin to unlock your account.");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            if (loginAttempts.recordFailure(user.getId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Too many wrong passwords. Your account is locked for "
                        + LoginAttempts.LOCK_FOR.toMinutes() + " minutes.");
            }
            throw wrongPassword();
        }
        if (!user.isActive()) {
            audit.logAlways(actor, AuditAction.LOGIN_FAILED, "User", user.getId(), "Sign-in refused: account disabled");
            throw new ApiException(HttpStatus.FORBIDDEN, "This account is disabled");
        }
        user.setFailedLogins(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        audit.logAs(actor, AuditAction.LOGIN, "User", user.getId(), "Signed in");
        return issue(user);
    }

    /** Someone changes their own password; other sessions are signed out and this one renewed. */
    @Transactional
    public Session changePassword(Long userId, String current, String next) {
        User user = users.findWithBranchById(userId).orElseThrow();
        if (!passwordEncoder.matches(current, user.getPasswordHash())) {
            throw ApiException.badRequest("Your current password is not right");
        }
        if (passwordEncoder.matches(next, user.getPasswordHash())) {
            throw ApiException.badRequest("Choose a password different from your current one");
        }
        user.setPasswordHash(passwordEncoder.encode(next));
        user.setMustChangePassword(false);
        user.setPasswordChangedAt(Instant.now());
        refreshTokens.revokeAllForUser(user.getId());
        audit.logAs(new Actor(user.getId(), user.getUsername(), user.getBranch().getId()), AuditAction.PASSWORD_CHANGED,
                "User", user.getId(), "Changed own password");
        return issue(user);
    }

    private static ApiException wrongPassword() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Wrong username or password");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Rotates the refresh token. Reusing a token that was already replaced revokes every session of
     * that user (someone else may hold it), with one exception: within a short grace period after
     * the rotation, the browser most likely never got the response (a reload or a dropped
     * connection mid-refresh), so it gets a new token instead of being signed out. A token revoked
     * by signing out, a password change or an admin never gets that grace.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Session refresh(String rawToken) {
        RefreshToken token = refreshTokens.findByTokenHash(hash(rawToken))
                .orElseThrow(AuthService::sessionExpired);
        User user = token.getUser();
        Instant now = Instant.now();
        if (token.isRevoked()) {
            if (withinReuseGrace(token, now) && user.isActive() && !token.getExpiresAt().isBefore(now)) {
                log.info("Refresh token for {} reused {} ms after rotation; treating it as a lost response", user.getUsername(),
                        Duration.between(token.getRotatedAt(), now).toMillis());
                return issue(user);
            }
            refreshTokens.revokeAllForUser(user.getId());
            String when = token.getRotatedAt() == null ? "" : " (" + Duration.between(token.getRotatedAt(), now).toSeconds()
                    + " s after it was replaced)";
            audit.logAs(new Actor(user.getId(), user.getUsername(), user.getBranch().getId()),
                    AuditAction.SESSION_REUSE_DETECTED, "User", user.getId(),
                    "An old sign-in token was reused" + when + "; all sessions were signed out");
            throw sessionExpired();
        }
        if (token.getExpiresAt().isBefore(now) || !user.isActive()) {
            throw sessionExpired();
        }
        token.setRevoked(true);
        token.setRotatedAt(now);
        return issue(user);
    }

    private boolean withinReuseGrace(RefreshToken token, Instant now) {
        Duration grace = props.jwt().refreshReuseGrace();
        return token.getRotatedAt() != null && grace != null && !grace.isNegative()
                && !now.isAfter(token.getRotatedAt().plus(grace));
    }

    @Transactional
    public void logout(String rawToken) {
        refreshTokens.findByTokenHash(hash(rawToken)).ifPresent(t -> {
            t.setRevoked(true);
            t.setRotatedAt(null);
            User u = t.getUser();
            audit.logAs(new Actor(u.getId(), u.getUsername(), u.getBranch().getId()), AuditAction.LOGOUT, "User",
                    u.getId(), "Signed out");
        });
    }

    private Session issue(User user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.save(new RefreshToken(user, hash(raw), Instant.now().plus(props.jwt().refreshTokenTtl())));
        var tokens = new TokenResponse(jwtService.createAccessToken(user), jwtService.accessTokenTtlSeconds(),
                UserDto.from(user));
        return new Session(tokens, raw);
    }

    private static ApiException sessionExpired() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Session expired, please sign in again");
    }

    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
