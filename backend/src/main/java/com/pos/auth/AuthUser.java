package com.pos.auth;

import com.pos.access.Permission;
import com.pos.access.RoleAccess;
import com.pos.common.SignedInUser;

/**
 * The signed-in person for this request. Identity comes from the access token; role and home
 * branch are looked up fresh (see AccessService). While {@code mustChangePassword} is set, only
 * the password-change endpoints work.
 */
/** {@code sessionId}: the sign-in the request's access token belongs to (null for tokens from before sessions were kept). */
public record AuthUser(Long id, String username, String fullName, RoleAccess role, Long branchId,
                       boolean mustChangePassword, String sessionId) implements SignedInUser {

    public boolean has(Permission p) {
        return role.has(p);
    }

    /** May switch branch and see or act on every branch. */
    public boolean allBranches() {
        return role.has(Permission.ALL_BRANCHES);
    }

    public boolean isSuperAdmin() {
        return role.superAdmin();
    }
}
