package com.pos.auth;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.auth.UserSession.EndReason;
import com.pos.common.ApiException;

/**
 * Signed-in sessions: listing them and ending them. Ending a session revokes its refresh token (the
 * device can't renew) and refuses its access token at once (EndedSessions), so the device is signed
 * out on its next request. Everyone manages their own; the super admin anyone's.
 */
@Service
public class SessionService {

    private final UserSessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final EndedSessions ended;
    private final AuditService audit;

    public SessionService(UserSessionRepository sessions, RefreshTokenRepository refreshTokens, UserRepository users,
                          EndedSessions ended, AuditService audit) {
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.ended = ended;
        this.audit = audit;
    }

    /**
     * A signed-in device. {@code current} marks the session the request came from.
     */
    public record SessionDto(String id, Long userId, String username, String fullName, String roleName, String device, String browser,
                             String os, DeviceNames.Kind kind, String ipAddress, Instant createdAt, Instant lastUsedAt,
                             Instant expiresAt, boolean current) {
        static SessionDto from(UserSession s, String currentId) {
            var d = DeviceNames.describe(s.getUserAgent());
            User u = s.getUser();
            return new SessionDto(s.getId(), u.getId(), u.getUsername(), u.getFullName(), u.getRole().getName(), d.label(), d.browser(),
                    d.os(), d.kind(), s.getIpAddress(), s.getCreatedAt(), s.getLastUsedAt(), s.getExpiresAt(), s.getId().equals(currentId));
        }
    }

    // ---------------------------------------------------------------- your own

    @Transactional(readOnly = true)
    public List<SessionDto> mine(AuthUser me) {
        return sessions.findActiveByUser(me.id(), Instant.now()).stream().map(s -> SessionDto.from(s, me.sessionId())).toList();
    }

    /** Ends one of your own sessions (another device; signing out of this one is /auth/logout). */
    @Transactional
    public void endMine(String id, AuthUser me) {
        UserSession s = sessions.findById(id).filter(x -> x.getUser().getId().equals(me.id()))
                .orElseThrow(() -> ApiException.notFound("Session"));
        if (!s.isActive(Instant.now())) {
            return;
        }
        end(s, EndReason.ENDED, me.id());
        audit.log(AuditAction.SESSION_ENDED, "User", me.id(), "Signed out own session on " + describe(s));
    }

    /** Ends all your sessions except the one you are using. */
    @Transactional
    public int endMyOthers(AuthUser me) {
        int n = 0;
        for (UserSession s : sessions.findActiveByUser(me.id(), Instant.now())) {
            if (!s.getId().equals(me.sessionId())) {
                end(s, EndReason.ENDED, me.id());
                n++;
            }
        }
        if (n > 0) {
            audit.log(AuditAction.SESSION_ENDED, "User", me.id(), "Signed out own other sessions (" + n + ")");
        }
        return n;
    }

    // ---------------------------------------------------------------- the super admin

    @Transactional(readOnly = true)
    public List<SessionDto> all(AuthUser me) {
        return sessions.findAllActive(Instant.now()).stream().map(s -> SessionDto.from(s, me.sessionId())).toList();
    }

    @Transactional(readOnly = true)
    public List<SessionDto> ofUser(Long userId, AuthUser me) {
        return sessions.findActiveByUser(userId, Instant.now()).stream().map(s -> SessionDto.from(s, me.sessionId())).toList();
    }

    @Transactional
    public void endAny(String id, AuthUser admin) {
        UserSession s = sessions.findById(id).orElseThrow(() -> ApiException.notFound("Session"));
        if (!s.isActive(Instant.now())) {
            return;
        }
        end(s, EndReason.ADMIN, admin.id());
        User u = s.getUser();
        audit.log(AuditAction.SESSION_ENDED, "User", u.getId(), "Signed " + u.getUsername() + " out on " + describe(s));
    }

    // ---------------------------------------------------------------- used by sign-in and user management

    /** Ends every session of a user (sign out everywhere, a password or account change, a reused token). */
    @Transactional
    public int endAll(Long userId, EndReason reason, Long byUserId) {
        refreshTokens.revokeAllForUser(userId);
        List<UserSession> active = sessions.findActiveByUser(userId, Instant.now());
        active.forEach(s -> markEnded(s, reason, byUserId));
        return active.size();
    }

    @Transactional
    public void end(UserSession s, EndReason reason, Long byUserId) {
        refreshTokens.revokeSession(s.getId());
        markEnded(s, reason, byUserId);
    }

    private void markEnded(UserSession s, EndReason reason, Long byUserId) {
        s.setEndedAt(Instant.now());
        s.setEndReason(reason);
        s.setEndedBy(byUserId == null ? null : users.getReferenceById(byUserId));
        // A changed role, branch or status applies from the next request anyway, so the open screen
        // carries on with it; every other ending cuts the session off at once.
        if (reason != EndReason.ACCOUNT_CHANGED) {
            ended.add(s.getId());
        }
    }

    private static String describe(UserSession s) {
        return DeviceNames.describe(s.getUserAgent()).label() + (s.getIpAddress() == null ? "" : " (" + s.getIpAddress() + ")");
    }
}
