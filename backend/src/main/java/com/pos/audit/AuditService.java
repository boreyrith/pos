package com.pos.audit;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.pos.auth.AuthUser;

import tools.jackson.databind.ObjectMapper;

/**
 * Writes audit entries. {@link #log} joins the caller's transaction, so an action that rolls back
 * leaves no entry. {@link #logAlways} commits on its own, for events that must be kept even when
 * the request fails (a wrong password, a lockout).
 * Who, from where and in which branch are taken from the current request.
 */
@Service
public class AuditService {

    /** Who did it, when the request isn't signed in yet (sign-in) or acts for someone else. */
    public record Actor(Long userId, String username, Long branchId) {
    }

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;
    private final ObjectMapper json;

    public AuditService(AuditLogRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void log(AuditAction action, String entityType, Object entityId, String summary) {
        write(null, action, entityType, entityId, summary, null);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void log(AuditAction action, String entityType, Object entityId, String summary, Changes changes) {
        write(null, action, entityType, entityId, summary, changes == null ? null : changes.asDetails());
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void log(AuditAction action, String entityType, Object entityId, String summary, Map<String, Object> details) {
        write(null, action, entityType, entityId, summary, details);
    }

    /** Same, for a known person who isn't the signed-in user (e.g. at sign-in). */
    @Transactional(propagation = Propagation.REQUIRED)
    public void logAs(Actor actor, AuditAction action, String entityType, Object entityId, String summary) {
        write(actor, action, entityType, entityId, summary, null);
    }

    /** Commits even if the surrounding request fails. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAlways(Actor actor, AuditAction action, String entityType, Object entityId, String summary) {
        write(actor, action, entityType, entityId, summary, null);
    }

    private void write(Actor actor, AuditAction action, String entityType, Object entityId, String summary,
                       Map<String, Object> details) {
        AuditLog a = new AuditLog();
        Actor who = actor != null ? actor : currentActor();
        if (who != null) {
            a.setUserId(who.userId());
            a.setUsername(who.username());
            a.setBranchId(who.branchId());
        }
        a.setAction(action);
        a.setCategory(action.category());
        a.setEntityType(entityType);
        a.setEntityId(entityId == null ? null : truncate(String.valueOf(entityId), 40));
        a.setSummary(truncate(summary, 500));
        if (details != null && !details.isEmpty()) {
            try {
                a.setDetails(json.writeValueAsString(new LinkedHashMap<>(details)));
            } catch (RuntimeException e) {
                log.warn("Could not write audit details for {}", action, e);
            }
        }
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            var req = attrs.getRequest();
            String forwarded = req.getHeader("X-Forwarded-For");
            a.setIp(truncate(forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : req.getRemoteAddr(), 64));
            a.setUserAgent(truncate(req.getHeader("User-Agent"), 255));
        }
        repository.save(a);
    }

    private static Actor currentActor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser u) {
            return new Actor(u.id(), u.username(), u.branchId());
        }
        return null;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
