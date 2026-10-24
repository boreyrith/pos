package com.pos.access;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import com.pos.auth.User;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A named set of permissions. Built-in roles ({@code builtIn}) can't be deleted or renamed; the
 * Super Admin role ({@code superAdmin}) has every permission, including ones added later, and
 * can't be edited at all.
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
public class Role {

    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, unique = true, length = 60)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    @Column(name = "super_admin", nullable = false)
    private boolean superAdmin;

    /** The most "Discount items" may take off a line, in %; null = no limit. Ignored for Super Admin. */
    @Column(name = "max_item_discount_pct", precision = 5, scale = 2)
    private java.math.BigDecimal maxItemDiscountPct;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission", length = 40)
    @Enumerated(EnumType.STRING)
    private Set<Permission> permissions = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    /** What the role can actually do: everything for the Super Admin. */
    public Set<Permission> effectivePermissions() {
        return superAdmin ? EnumSet.allOf(Permission.class) : Permission.withImplied(permissions);
    }
}
