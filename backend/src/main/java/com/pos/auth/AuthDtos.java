package com.pos.auth;

import java.util.List;

import com.pos.access.Permission;
import com.pos.access.Role;
import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    /** The interface language: "en" or "km". */
    public record LanguageRequest(@NotNull @Pattern(regexp = "en|km") String language) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    /** A user's role, for display and for picking one. */
    /** @param maxItemDiscountPct the most the role may take off an item with "Discount items"; null = no limit */
    public record RoleRef(Long id, String code, String name, boolean superAdmin, java.math.BigDecimal maxItemDiscountPct) {
        public static RoleRef from(Role r) {
            return new RoleRef(r.getId(), r.getCode(), r.getName(), r.isSuperAdmin(), r.isSuperAdmin() ? null : r.getMaxItemDiscountPct());
        }
    }

    /** {@code permissions} is what the user can do (every permission for a Super Admin). */
    public record UserDto(Long id, String username, String fullName, String email, String phone, String language, RoleRef role,
                          List<Permission> permissions, boolean active, Long branchId, String branchName,
                          Instant lastLoginAt, boolean locked, Instant lockedUntil, boolean mustChangePassword,
                          Instant passwordChangedAt) {
        public static UserDto from(User u) {
            Instant now = Instant.now();
            return new UserDto(u.getId(), u.getUsername(), u.getFullName(), u.getEmail(), u.getPhone(), u.getLanguage(), RoleRef.from(u.getRole()),
                    u.getRole().effectivePermissions().stream().sorted().toList(),
                    u.isActive(), u.getBranch().getId(), u.getBranch().getName(), u.getLastLoginAt(), u.isLocked(now),
                    u.isLocked(now) ? u.getLockedUntil() : null, u.isMustChangePassword(), u.getPasswordChangedAt());
        }
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 8, max = 72) String newPassword) {
    }

    public record TokenResponse(String accessToken, long expiresIn, UserDto user) {
    }
}
