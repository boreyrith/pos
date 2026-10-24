package com.pos.access;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.auth.UserRepository;

/**
 * Who may do what, looked up on every request. The sign-in token only says who someone is; their
 * role, branch and whether the account is still active come from here, so changing a user or a
 * role takes effect on their next request. Both are cached in memory and evicted when they change
 * (this assumes one API instance; with several, add a shared cache or a short expiry).
 */
@Service
public class AccessService {

    /** A user's current role, home branch and status. */
    public record UserAccess(Long roleId, Long branchId, boolean active) {
    }

    private final UserRepository users;
    private final RoleRepository roles;
    private final Map<Long, UserAccess> userCache = new ConcurrentHashMap<>();
    private final Map<Long, RoleAccess> roleCache = new ConcurrentHashMap<>();

    public AccessService(UserRepository users, RoleRepository roles) {
        this.users = users;
        this.roles = roles;
    }

    /** Empty when the user no longer exists. */
    @Transactional(readOnly = true)
    public Optional<UserAccess> user(Long userId) {
        UserAccess cached = userCache.get(userId);
        if (cached != null) {
            return Optional.of(cached);
        }
        return users.findById(userId).map(u -> {
            UserAccess a = new UserAccess(u.getRole().getId(), u.getBranch().getId(), u.isActive());
            userCache.put(userId, a);
            return a;
        });
    }

    @Transactional(readOnly = true)
    public RoleAccess role(Long roleId) {
        RoleAccess cached = roleCache.get(roleId);
        if (cached != null) {
            return cached;
        }
        RoleAccess a = RoleAccess.from(roles.findById(roleId).orElseThrow());
        roleCache.put(roleId, a);
        return a;
    }

    /** Call when a user's role, branch or status changes. */
    public void evictUser(Long userId) {
        userCache.remove(userId);
    }

    /** Call when a role's permissions change or it is deleted. */
    public void evictRole(Long roleId) {
        roleCache.remove(roleId);
    }
}
