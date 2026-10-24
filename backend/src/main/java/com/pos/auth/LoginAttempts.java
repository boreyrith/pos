package com.pos.auth;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.AuditService.Actor;

/**
 * Counts wrong passwords and locks the account after too many. Runs in its own transaction so the
 * count and the audit entry are kept even though the sign-in request fails.
 */
@Component
public class LoginAttempts {

    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK_FOR = Duration.ofMinutes(15);

    private final UserRepository users;
    private final AuditService audit;

    public LoginAttempts(UserRepository users, AuditService audit) {
        this.users = users;
        this.audit = audit;
    }

    /** Returns true if this failure locked the account. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(Long userId) {
        User user = users.findWithBranchById(userId).orElseThrow();
        Actor actor = new Actor(user.getId(), user.getUsername(), user.getBranch().getId());
        int failures = user.getFailedLogins() + 1;
        if (failures >= MAX_FAILURES) {
            user.setFailedLogins(0);
            user.setLockedUntil(Instant.now().plus(LOCK_FOR));
            audit.logAs(actor, AuditAction.ACCOUNT_LOCKED, "User", user.getId(),
                    "Account locked for " + LOCK_FOR.toMinutes() + " minutes after " + MAX_FAILURES + " wrong passwords");
            return true;
        }
        user.setFailedLogins(failures);
        audit.logAs(actor, AuditAction.LOGIN_FAILED, "User", user.getId(),
                "Wrong password (" + failures + " of " + MAX_FAILURES + ")");
        return false;
    }
}
