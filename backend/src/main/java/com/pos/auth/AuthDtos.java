package com.pos.auth;

import java.util.List;

import com.pos.access.Permission;
import com.pos.access.Role;
import java.time.Instant;
import java.time.LocalDate;

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

    /** {@code remember}: stay signed in after the browser closes (default yes). */
    public record LoginRequest(@NotBlank String username, @NotBlank String password, Boolean remember) {
    }

    /** A user's role, for display and for picking one. */
    /** @param maxItemDiscountPct the most the role may take off an item with "Discount items"; null = no limit */
    public record RoleRef(Long id, String code, String name, boolean superAdmin, java.math.BigDecimal maxItemDiscountPct) {
        public static RoleRef from(Role r) {
            return new RoleRef(r.getId(), r.getCode(), r.getName(), r.isSuperAdmin(), r.isSuperAdmin() ? null : r.getMaxItemDiscountPct());
        }
    }

    /**
     * {@code fullName} is the display name (receipts, history); {@code nameKm}, {@code nameLatin},
     * {@code gender} and {@code dateOfBirth} are optional personal details. {@code permissions} is
     * what the user can do (every permission for a Super Admin).
     */
    public record UserDto(Long id, String username, String fullName, String nameKm, String nameLatin, Gender gender,
                          LocalDate dateOfBirth, String email, String phone, String language, RoleRef role,
                          List<Permission> permissions, boolean active, Long branchId, String branchName,
                          Instant lastLoginAt, boolean locked, Instant lockedUntil, boolean mustChangePassword,
                          Instant passwordChangedAt, boolean hasPhoto, int photoVersion, String avatar) {
        public static UserDto from(User u) {
            Instant now = Instant.now();
            return new UserDto(u.getId(), u.getUsername(), u.getFullName(), u.getNameKm(), u.getNameLatin(), u.getGender(),
                    u.getDateOfBirth(), u.getEmail(), u.getPhone(), u.getLanguage(), RoleRef.from(u.getRole()),
                    u.getRole().effectivePermissions().stream().sorted().toList(),
                    u.isActive(), u.getBranch().getId(), u.getBranch().getName(), u.getLastLoginAt(), u.isLocked(now),
                    u.isLocked(now) ? u.getLockedUntil() : null, u.isMustChangePassword(), u.getPasswordChangedAt(),
                    u.isHasPhoto(), u.getPhotoVersion(), u.getAvatar());
        }
    }

    /** A built-in avatar's code, or null for none (initials, or the photo if there is one). */
    public record AvatarRequest(@Size(max = 20) String avatar) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 8, max = 72) String newPassword) {
    }

    public record TokenResponse(String accessToken, long expiresIn, UserDto user) {
    }
}
