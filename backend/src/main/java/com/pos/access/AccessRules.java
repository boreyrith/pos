package com.pos.access;

import org.springframework.http.HttpStatus;

import com.pos.auth.AuthUser;
import com.pos.auth.User;
import com.pos.common.ApiException;

/**
 * Rules that stop people from giving themselves or others more than they have:
 * <ul>
 * <li>Only a Super Admin can assign the Super Admin role or touch a Super Admin's account.</li>
 * <li>Anyone else can only assign roles, and grant permissions, that they hold themselves, and
 * can only manage accounts whose role is within their own permissions.</li>
 * <li>Without "All branches", people manage only the staff of their own branch.</li>
 * </ul>
 */
public final class AccessRules {

    private AccessRules() {
    }

    /** Whether {@code actor} may give {@code role} to someone. */
    public static boolean canAssign(AuthUser actor, Role role) {
        if (actor.isSuperAdmin()) {
            return true;
        }
        return !role.isSuperAdmin() && actor.role().permissions().containsAll(role.effectivePermissions())
                && withinDiscountLimit(actor, role.effectivePermissions(), role.getMaxItemDiscountPct());
    }

    /** A role that may discount items can't allow more than the actor's own limit. */
    public static boolean withinDiscountLimit(AuthUser actor, java.util.Set<Permission> perms, java.math.BigDecimal max) {
        java.math.BigDecimal own = actor.role().maxItemDiscountPct();
        if (actor.isSuperAdmin() || own == null || !perms.contains(Permission.DISCOUNT_ITEMS)) {
            return true;
        }
        return max != null && max.compareTo(own) <= 0;
    }

    public static void requireAssignable(AuthUser actor, Role role) {
        if (!canAssign(actor, role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, role.isSuperAdmin()
                    ? "Only a Super Admin can give someone the Super Admin role"
                    : "You can't give the role " + role.getName() + ": it has permissions you don't have");
        }
    }

    /** Whether {@code actor} may edit, reset or sign out {@code target}. */
    public static boolean canManage(AuthUser actor, User target) {
        if (actor.isSuperAdmin()) {
            return true;
        }
        if (!actor.allBranches() && !target.getBranch().getId().equals(actor.branchId())) {
            return false;
        }
        return canAssign(actor, target.getRole());
    }

    public static void requireManageable(AuthUser actor, User target) {
        if (!canManage(actor, target)) {
            throw new ApiException(HttpStatus.FORBIDDEN, target.getRole().isSuperAdmin()
                    ? "Only a Super Admin can change a Super Admin's account"
                    : !actor.allBranches() && !target.getBranch().getId().equals(actor.branchId())
                            ? "You can only manage staff in your own branch"
                            : "You can't change " + target.getUsername() + ": their role has permissions you don't have");
        }
    }

    public static void requireBranch(AuthUser actor, Long branchId) {
        if (!actor.allBranches() && !branchId.equals(actor.branchId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only add staff to your own branch");
        }
    }
}
