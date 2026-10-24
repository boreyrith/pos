package com.pos.user;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pos.access.RoleRepository;
import com.pos.access.Role;
import com.pos.access.AccessService;
import com.pos.access.AccessRules;
import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.Changes;
import com.pos.auth.AuthDtos.UserDto;
import com.pos.auth.AuthUser;
import com.pos.auth.RefreshTokenRepository;
import com.pos.auth.User;
import com.pos.auth.UserRepository;
import com.pos.common.ApiException;
import com.pos.inventory.Branch;
import com.pos.inventory.BranchRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Staff accounts. Viewing needs "View users", changes "Manage users", within the limits of
 * {@link AccessRules}. Every change is written to the audit log.
 */
@RestController
@RequestMapping("/users")
@PreAuthorize("hasAuthority('USERS_VIEW')")
public class UserController {

    /** {@code mustChangePassword} defaults to true: the person picks their own password at first sign-in. */
    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 50) @Pattern(regexp = "[a-zA-Z0-9._-]+") String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 100) String fullName,
            @Email @Size(max = 100) String email,
            @Size(max = 50) String phone,
            @NotNull Long roleId,
            @NotNull Long branchId,
            Boolean mustChangePassword) {
    }

    public record UpdateUserRequest(@NotBlank @Size(max = 100) String fullName, @Email @Size(max = 100) String email,
                                    @Size(max = 50) String phone, @NotNull Long roleId, @NotNull Boolean active,
                                    @NotNull Long branchId) {
    }

    public record ResetPasswordRequest(@NotBlank @Size(min = 8, max = 72) String password, Boolean mustChangePassword) {
    }

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final BranchRepository branches;
    private final AuditService audit;
    private final RoleRepository roles;
    private final AccessService access;

    public UserController(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                          BranchRepository branches, AuditService audit, RoleRepository roles, AccessService access) {
        this.roles = roles;
        this.access = access;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.branches = branches;
        this.audit = audit;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<UserDto> list(@AuthenticationPrincipal AuthUser me) {
        // Without "All branches", only their own branch's staff.
        return users.findAllByOrderByUsernameAsc().stream()
                .filter(u -> me.allBranches() || u.getBranch().getId().equals(me.branchId()))
                .map(UserDto::from).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public UserDto get(@PathVariable Long id, @AuthenticationPrincipal AuthUser me) {
        User user = find(id);
        if (!me.allBranches() && !user.getBranch().getId().equals(me.branchId())) {
            throw ApiException.notFound("User");
        }
        return UserDto.from(user);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public UserDto create(@Valid @RequestBody CreateUserRequest req, @AuthenticationPrincipal AuthUser me) {
        if (users.existsByUsername(req.username())) {
            throw ApiException.conflict("Username is already taken");
        }
        Role role = role(req.roleId());
        AccessRules.requireAssignable(me, role);
        AccessRules.requireBranch(me, req.branchId());
        User user = new User(req.username(), passwordEncoder.encode(req.password()), req.fullName().trim(), role,
                branch(req.branchId()));
        user.setEmail(blankToNull(req.email()));
        user.setPhone(blankToNull(req.phone()));
        user.setMustChangePassword(req.mustChangePassword() == null || req.mustChangePassword());
        users.saveAndFlush(user);
        audit.log(AuditAction.USER_CREATED, "User", user.getId(), "Created " + user.getUsername() + " ("
                + user.getRole().getName() + ", " + user.getBranch().getName() + ")");
        return UserDto.from(user);
    }

    @PutMapping("/{id}")
    @Transactional
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public UserDto update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest req, @AuthenticationPrincipal AuthUser me) {
        User user = find(id);
        AccessRules.requireManageable(me, user);
        Role role = req.roleId().equals(user.getRole().getId()) ? user.getRole() : role(req.roleId());
        boolean self = user.getId().equals(me.id());
        if (self && (!req.active() || !role.getId().equals(user.getRole().getId()))) {
            throw ApiException.badRequest("You can't disable your own account or change your own role");
        }
        AccessRules.requireAssignable(me, role);
        if (!req.branchId().equals(user.getBranch().getId())) {
            AccessRules.requireBranch(me, req.branchId());
        }
        boolean losesSuperAdmin = user.getRole().isSuperAdmin() && user.isActive() && (!role.isSuperAdmin() || !req.active());
        if (losesSuperAdmin && users.countByRoleSuperAdminTrueAndActiveTrue() <= 1) {
            throw ApiException.badRequest("Keep at least one active Super Admin");
        }
        Branch branch = req.branchId().equals(user.getBranch().getId()) ? user.getBranch() : branch(req.branchId());
        Changes changes = new Changes()
                .add("Name", user.getFullName(), req.fullName().trim())
                .add("Email", user.getEmail(), blankToNull(req.email()))
                .add("Phone", user.getPhone(), blankToNull(req.phone()))
                .add("Role", user.getRole().getName(), role.getName())
                .add("Branch", user.getBranch().getName(), branch.getName())
                .add("Active", user.isActive() ? "yes" : "no", req.active() ? "yes" : "no");
        boolean signOut = !user.getRole().getId().equals(role.getId()) || user.isActive() != req.active()
                || !user.getBranch().getId().equals(branch.getId());

        user.setFullName(req.fullName().trim());
        user.setEmail(blankToNull(req.email()));
        user.setPhone(blankToNull(req.phone()));
        user.setRole(role);
        user.setActive(req.active());
        user.setBranch(branch);
        // Role, branch and status apply from their next request; other devices are signed out too.
        if (signOut) {
            refreshTokens.revokeAllForUser(user.getId());
        }
        access.evictUser(user.getId());
        if (!changes.isEmpty()) {
            audit.log(AuditAction.USER_UPDATED, "User", user.getId(), "Updated " + user.getUsername() + ": "
                    + changes.describe(), changes);
        }
        return UserDto.from(user);
    }

    /** Sets a new password and signs them out everywhere. Usually they must choose their own next. */
    @PostMapping("/{id}/reset-password")
    @Transactional
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public UserDto resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest req,
                                 @AuthenticationPrincipal AuthUser me) {
        User user = find(id);
        AccessRules.requireManageable(me, user);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setMustChangePassword(req.mustChangePassword() == null || req.mustChangePassword());
        user.setPasswordChangedAt(Instant.now());
        user.setFailedLogins(0);
        user.setLockedUntil(null);
        refreshTokens.revokeAllForUser(user.getId());
        audit.log(AuditAction.USER_PASSWORD_RESET, "User", user.getId(), "Reset password for " + user.getUsername()
                + (user.isMustChangePassword() ? " (must change it at next sign-in)" : ""));
        return UserDto.from(user);
    }

    @PostMapping("/{id}/unlock")
    @Transactional
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public UserDto unlock(@PathVariable Long id, @AuthenticationPrincipal AuthUser me) {
        User user = find(id);
        AccessRules.requireManageable(me, user);
        user.setLockedUntil(null);
        user.setFailedLogins(0);
        audit.log(AuditAction.USER_UNLOCKED, "User", user.getId(), "Unlocked " + user.getUsername());
        return UserDto.from(user);
    }

    /** Ends their sessions on every device. Their current screen keeps working for up to 15 minutes. */
    @PostMapping("/{id}/sign-out")
    @Transactional
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public UserDto signOutEverywhere(@PathVariable Long id, @AuthenticationPrincipal AuthUser me) {
        User user = find(id);
        AccessRules.requireManageable(me, user);
        int revoked = refreshTokens.revokeAllForUser(user.getId());
        audit.log(AuditAction.USER_SESSIONS_REVOKED, "User", user.getId(), "Signed " + user.getUsername()
                + " out everywhere (" + revoked + " session" + (revoked == 1 ? "" : "s") + ")");
        return UserDto.from(user);
    }

    private User find(Long id) {
        return users.findWithBranchById(id).orElseThrow(() -> ApiException.notFound("User"));
    }

    private Branch branch(Long id) {
        Branch b = branches.findById(id).orElseThrow(() -> ApiException.notFound("Branch"));
        if (!b.isActive()) {
            throw ApiException.badRequest(b.getName() + " is not active");
        }
        return b;
    }

    private Role role(Long id) {
        return roles.findById(id).orElseThrow(() -> ApiException.badRequest("That role doesn't exist"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
