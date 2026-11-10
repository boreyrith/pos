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
import com.pos.common.AppProperties;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** A login result: the access token response plus a raw refresh token for the cookie. */
    /** {@code remember}: whether the refresh cookie should outlive the browser ("Remember me" at sign-in). */
    public record Session(TokenResponse tokens, String refreshToken, boolean remember) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AppProperties props;
    private final AuditService audit;
    private final LoginAttempts loginAttempts;
    private final UserSessionRepository userSessions;
    private final SessionService sessions;
    private final SignInThrottle throttle;
    /** A real BCrypt hash of a random password, checked for unknown usernames. */
    private final String dummyHash;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AppProperties props, AuditService audit, LoginAttempts loginAttempts,
                       UserSessionRepository userSessions, SessionService sessions, SignInThrottle throttle) {
        this.throttle = throttle;
        this.dummyHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
        this.userSessions = userSessions;
        this.sessions = sessions;
        this.audit = audit;
        this.loginAttempts = loginAttempts;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.props = props;
    }

    @Transactional
    public Session login(String username, String password, boolean remember, ClientInfo client) {
        String name = username.trim();
        Instant now = Instant.now();
        String ip = client == null ? null : client.ip();
        long wait = throttle.retryAfter(ip, now);
        if (wait > 0) {
            long minutes = Math.max(1, (wait + 59) / 60);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many sign-in attempts from this device. Try again in " + minutes
                    + " minute" + (minutes == 1 ? "" : "s") + ".", "TOO_MANY_ATTEMPTS");
        }
        User user = users.findByUsername(name).orElse(null);
        if (user == null) {
            // Check a password anyway, so an unknown name takes as long as a wrong password and can't be told apart.
            passwordEncoder.matches(password, dummyHash);
            throttle.failed(ip, now);
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
            throttle.failed(ip, now);
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
        audit.logAs(actor, AuditAction.LOGIN, "User", user.getId(), "Signed in on "
                + DeviceNames.describe(client == null ? null : client.userAgent()).label());
        return issue(user, null, client, remember);
    }

    /** Someone changes their own password; other sessions are signed out and this one renewed. */
    @Transactional
    public Session changePassword(Long userId, String current, String next, boolean remember, ClientInfo client) {
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
        sessions.endAll(user.getId(), UserSession.EndReason.PASSWORD_CHANGED, user.getId());
        audit.logAs(new Actor(user.getId(), user.getUsername(), user.getBranch().getId()), AuditAction.PASSWORD_CHANGED,
                "User", user.getId(), "Changed own password");
        return issue(user, null, client, remember);
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
    public Session refresh(String rawToken, ClientInfo client) {
        RefreshToken token = refreshTokens.findByTokenHash(hash(rawToken))
                .orElseThrow(AuthService::sessionExpired);
        User user = token.getUser();
        Instant now = Instant.now();
        if (token.isRevoked() && token.getSession() != null && token.getSession().getEndedAt() != null) {
            // Its session was ended on purpose (signed out, or ended from elsewhere): not a stolen token.
            throw sessionExpired();
        }
        if (token.isRevoked()) {
            if (withinReuseGrace(token, now) && user.isActive() && !token.getExpiresAt().isBefore(now)) {
                log.info("Refresh token for {} reused {} ms after rotation; treating it as a lost response", user.getUsername(),
                        Duration.between(token.getRotatedAt(), now).toMillis());
                return issue(user, token.getSession(), client, token.getSession() == null || token.getSession().isRemember());
            }
            sessions.endAll(user.getId(), UserSession.EndReason.TOKEN_REUSED, null);
            String when = token.getRotatedAt() == null ? "" : " (" + Duration.between(token.getRotatedAt(), now).toSeconds()
                    + " s after it was replaced)";
            audit.logAs(new Actor(user.getId(), user.getUsername(), user.getBranch().getId()),
                    AuditAction.SESSION_REUSE_DETECTED, "User", user.getId(),
                    "An old sign-in token was reused" + when + "; all sessions were signed out");
            throw sessionExpired();
        }
        if (token.getExpiresAt().isBefore(now) || !user.isActive()
                || (token.getSession() != null && token.getSession().getEndedAt() != null)) {
            throw sessionExpired();
        }
        token.setRevoked(true);
        token.setRotatedAt(now);
        return issue(user, token.getSession(), client, token.getSession() == null || token.getSession().isRemember());
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
            if (t.getSession() != null && t.getSession().getEndedAt() == null) {
                sessions.end(t.getSession(), UserSession.EndReason.SIGNED_OUT, t.getUser().getId());
            }
            User u = t.getUser();
            audit.logAs(new Actor(u.getId(), u.getUsername(), u.getBranch().getId()), AuditAction.LOGOUT, "User",
                    u.getId(), "Signed out");
        });
    }

    /**
     * New tokens for a session: {@code session} null starts one (a sign-in, or a token from before
     * sessions were kept); otherwise it continues, noting where it was used from.
     */
    private Session issue(User user, UserSession session, ClientInfo client, boolean remember) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = Instant.now().plus(props.jwt().refreshTokenTtl());
        if (session == null) {
            session = userSessions.save(new UserSession(java.util.UUID.randomUUID().toString(), user, expires, client, remember));
        } else {
            session.seenFrom(client);
            session.setExpiresAt(expires);
        }
        refreshTokens.save(new RefreshToken(user, session, hash(raw), expires));
        var tokens = new TokenResponse(jwtService.createAccessToken(user, session.getId()), jwtService.accessTokenTtlSeconds(),
                UserDto.from(user));
        return new Session(tokens, raw, remember);
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
