package com.pos.auth;

import com.pos.access.Permission;
import com.pos.access.RoleAccess;

/**
 * The signed-in person for this request. Identity comes from the access token; role and home
 * branch are looked up fresh (see AccessService). While {@code mustChangePassword} is set, only
 * the password-change endpoints work.
 */
public record AuthUser(Long id, String username, String fullName, RoleAccess role, Long branchId,
                       boolean mustChangePassword) {

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
