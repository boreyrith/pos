package com.pos.access;

import java.math.BigDecimal;
import java.util.Set;

/** A role as the security layer sees it: cached, immutable, permissions already expanded. */
public record RoleAccess(Long id, String code, String name, boolean superAdmin, Set<Permission> permissions,
                         BigDecimal maxItemDiscountPct) {

    static RoleAccess from(Role r) {
        return new RoleAccess(r.getId(), r.getCode(), r.getName(), r.isSuperAdmin(), Set.copyOf(r.effectivePermissions()),
                r.isSuperAdmin() ? null : r.getMaxItemDiscountPct());
    }

    public boolean has(Permission p) {
        return superAdmin || permissions.contains(p);
    }
}
