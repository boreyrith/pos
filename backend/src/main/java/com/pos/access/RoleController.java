package com.pos.access;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.Changes;
import com.pos.auth.AuthUser;
import com.pos.auth.UserRepository;
import com.pos.common.ApiException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Roles and their permissions. Anyone who can see users can list roles (to pick one); "Manage
 * roles" creates, edits and deletes them. Built-in roles keep their name and can't be deleted; the
 * Super Admin role can't be changed. People can only grant permissions they hold, and can't edit
 * their own role, so nobody can raise their own access.
 */
@RestController
@RequestMapping("/roles")
public class RoleController {

    public record PermissionDto(Permission code, Permission.Module module, String moduleLabel, String label, String description,
                                Set<Permission> implies) {
    }

    /**
     * @param permissions what was ticked; {@code effective} adds what they imply (all for Super Admin)
     * @param assignable  whether the caller may give this role to someone
     * @param editable    whether the caller may change it
     */
    public record RoleDto(Long id, String code, String name, String description, boolean builtIn, boolean superAdmin,
                          List<Permission> permissions, List<Permission> effective, long users, boolean assignable,
                          boolean editable, java.math.BigDecimal maxItemDiscountPct, Instant updatedAt, String updatedBy) {
    }

    /** @param maxItemDiscountPct the most "Discount items" may take off a line, in %; null = no limit */
    public record RoleRequest(@NotBlank @Size(max = 60) String name, @Size(max = 255) String description,
                              @NotNull Set<Permission> permissions,
                              @jakarta.validation.constraints.DecimalMin("0") @jakarta.validation.constraints.DecimalMax("100")
                              @jakarta.validation.constraints.Digits(integer = 3, fraction = 2) java.math.BigDecimal maxItemDiscountPct) {
    }

    private final RoleRepository roles;
    private final UserRepository users;
    private final AccessService access;
    private final AuditService audit;

    public RoleController(RoleRepository roles, UserRepository users, AccessService access, AuditService audit) {
        this.roles = roles;
        this.users = users;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyAuthority('USERS_VIEW','ROLES_MANAGE')")
    public List<RoleDto> list(@AuthenticationPrincipal AuthUser me) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : roles.countUsers()) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return roles.findAllByOrderByBuiltInDescIdAsc().stream().map(r -> toDto(r, counts.getOrDefault(r.getId(), 0L), me)).toList();
    }

    /** The permission catalog, grouped by module, for the role editor. */
    @GetMapping("/permissions")
    @PreAuthorize("hasAnyAuthority('USERS_VIEW','ROLES_MANAGE')")
    public List<PermissionDto> permissions() {
        return Arrays.stream(Permission.values())
                .map(p -> new PermissionDto(p, p.module(), p.module().label(), p.label(), p.description(), p.implied()))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    @PreAuthorize("hasAuthority('ROLES_MANAGE')")
    public RoleDto create(@Valid @RequestBody RoleRequest req, @AuthenticationPrincipal AuthUser me) {
        String name = req.name().trim();
        if (roles.existsByNameIgnoreCase(name)) {
            throw ApiException.conflict("A role called " + name + " already exists");
        }
        Set<Permission> perms = normalize(req.permissions());
        requireGrantable(me, perms);
        requireDiscountLimit(me, perms, req.maxItemDiscountPct());
        Role r = new Role();
        r.setCode(uniqueCode(name));
        r.setName(name);
        r.setDescription(blankToNull(req.description()));
        r.setPermissions(perms);
        r.setMaxItemDiscountPct(req.maxItemDiscountPct());
        touch(r, me);
        roles.saveAndFlush(r);
        audit.log(AuditAction.ROLE_CREATED, "Role", r.getId(), "Created role " + name + ": " + describe(perms));
        return toDto(r, 0, me);
    }

    @PutMapping("/{id}")
    @Transactional
    @PreAuthorize("hasAuthority('ROLES_MANAGE')")
    public RoleDto update(@PathVariable Long id, @Valid @RequestBody RoleRequest req, @AuthenticationPrincipal AuthUser me) {
        Role r = find(id);
        requireEditable(me, r);
        String name = req.name().trim();
        if (r.isBuiltIn() && !name.equals(r.getName())) {
            throw ApiException.badRequest("Built-in roles keep their name");
        }
        if (!name.equalsIgnoreCase(r.getName()) && roles.existsByNameIgnoreCase(name)) {
            throw ApiException.conflict("A role called " + name + " already exists");
        }
        Set<Permission> perms = normalize(req.permissions());
        requireGrantable(me, perms);
        requireDiscountLimit(me, perms, req.maxItemDiscountPct());

        Set<Permission> added = EnumSet.noneOf(Permission.class);
        added.addAll(perms);
        added.removeAll(r.getPermissions());
        Set<Permission> removed = EnumSet.noneOf(Permission.class);
        removed.addAll(r.getPermissions());
        removed.removeAll(perms);
        Changes changes = new Changes()
                .add("Name", r.getName(), name)
                .add("Description", r.getDescription(), blankToNull(req.description()))
                .add("Permissions added", null, added.isEmpty() ? null : describe(added))
                .add("Permissions removed", null, removed.isEmpty() ? null : describe(removed))
                .add("Max item discount", limit(r.getMaxItemDiscountPct()), limit(req.maxItemDiscountPct()));

        r.setName(name);
        r.setDescription(blankToNull(req.description()));
        r.getPermissions().clear();
        r.getPermissions().addAll(perms);
        r.setMaxItemDiscountPct(req.maxItemDiscountPct());
        touch(r, me);
        roles.saveAndFlush(r);
        // Everyone with this role gets the new permissions on their next request.
        access.evictRole(r.getId());
        if (!changes.isEmpty()) {
            audit.log(AuditAction.ROLE_UPDATED, "Role", r.getId(), "Updated role " + name + ": " + changes.describe(), changes);
        }
        return toDto(r, users.countByRoleId(r.getId()), me);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    @PreAuthorize("hasAuthority('ROLES_MANAGE')")
    public void delete(@PathVariable Long id, @AuthenticationPrincipal AuthUser me) {
        Role r = find(id);
        if (r.isBuiltIn()) {
            throw ApiException.badRequest("Built-in roles can't be deleted");
        }
        requireEditable(me, r);
        long n = users.countByRoleId(r.getId());
        if (n > 0) {
            throw ApiException.conflict(n + (n == 1 ? " user has" : " users have") + " this role. Give them another role first.");
        }
        roles.delete(r);
        access.evictRole(r.getId());
        audit.log(AuditAction.ROLE_DELETED, "Role", r.getId(), "Deleted role " + r.getName());
    }

    private RoleDto toDto(Role r, long userCount, AuthUser me) {
        return new RoleDto(r.getId(), r.getCode(), r.getName(), r.getDescription(), r.isBuiltIn(), r.isSuperAdmin(),
                r.getPermissions().stream().sorted().toList(), r.effectivePermissions().stream().sorted().toList(), userCount,
                AccessRules.canAssign(me, r), canEdit(me, r), r.getMaxItemDiscountPct(), r.getUpdatedAt(),
                r.getUpdatedBy() == null ? null : r.getUpdatedBy().getFullName());
    }

    private static boolean canEdit(AuthUser me, Role r) {
        if (r.isSuperAdmin() || !me.has(Permission.ROLES_MANAGE)) {
            return false;
        }
        if (me.isSuperAdmin()) {
            return true;
        }
        // Not their own role (they could lock themselves out or raise their own access), and only
        // roles within what they hold.
        return !r.getId().equals(me.role().id()) && me.role().permissions().containsAll(r.effectivePermissions())
                && AccessRules.withinDiscountLimit(me, r.effectivePermissions(), r.getMaxItemDiscountPct());
    }

    private static void requireEditable(AuthUser me, Role r) {
        if (r.isSuperAdmin()) {
            throw ApiException.badRequest("The Super Admin role always has every permission and can't be changed");
        }
        if (!canEdit(me, r)) {
            throw new ApiException(HttpStatus.FORBIDDEN, r.getId().equals(me.role().id())
                    ? "You can't change your own role; ask a Super Admin"
                    : "You can't change " + r.getName() + ": it has permissions you don't have");
        }
    }

    private static void requireGrantable(AuthUser me, Set<Permission> perms) {
        if (me.isSuperAdmin()) {
            return;
        }
        Set<Permission> missing = EnumSet.noneOf(Permission.class);
        missing.addAll(perms);
        missing.removeAll(me.role().permissions());
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only give permissions you have yourself (not: " + describe(missing) + ")");
        }
    }

    private static void requireDiscountLimit(AuthUser me, Set<Permission> perms, java.math.BigDecimal max) {
        if (!AccessRules.withinDiscountLimit(me, perms, max)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only allow item discounts up to your own limit ("
                    + me.role().maxItemDiscountPct().stripTrailingZeros().toPlainString() + "%)");
        }
    }

    private static String limit(java.math.BigDecimal pct) {
        return pct == null ? "no limit" : pct.stripTrailingZeros().toPlainString() + "%";
    }

    /** Adds what the ticked permissions need, so a role never has a permission it can't use. */
    private static Set<Permission> normalize(Set<Permission> requested) {
        return Permission.withImplied(requested);
    }

    private String uniqueCode(String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "").toUpperCase();
        if (base.isEmpty()) {
            base = "ROLE";
        }
        base = "CUSTOM_" + (base.length() > 30 ? base.substring(0, 30) : base);
        String code = base;
        for (int i = 2; roles.existsByCode(code); i++) {
            code = base + "_" + i;
        }
        return code;
    }

    private Role find(Long id) {
        return roles.findById(id).orElseThrow(() -> ApiException.notFound("Role"));
    }

    private void touch(Role r, AuthUser me) {
        r.setUpdatedAt(Instant.now());
        r.setUpdatedBy(users.getReferenceById(me.id()));
    }

    private static String describe(Set<Permission> perms) {
        return perms.isEmpty() ? "no permissions" : perms.stream().sorted().map(Permission::label).collect(Collectors.joining(", "));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
