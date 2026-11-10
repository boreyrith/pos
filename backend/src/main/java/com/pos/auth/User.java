package com.pos.auth;

import com.pos.access.Role;
import com.pos.branch.Branch;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
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

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    /** Their name in Khmer script and in Latin letters (as on their ID card); optional. */
    @Column(name = "name_km", length = 100)
    private String nameKm;

    @Column(name = "name_latin", length = 100)
    private String nameLatin;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Gender gender;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(length = 100)
    private String email;

    @Column(length = 50)
    private String phone;

    /** Interface language: "en" (default) or "km". */
    @Column(nullable = false, length = 5)
    private String language = "en";

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** Wrong passwords since the last successful sign-in or lockout. */
    @Column(name = "failed_logins", nullable = false)
    private int failedLogins;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    /** Set when an admin creates the account or resets the password; cleared when they choose their own. */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id")
    private Role role;

    @Column(nullable = false)
    private boolean active = true;

    /** The branch this person works at. Sales, stock edits and reports default to it. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    /** Whether they added a photo (UserPhoto); the version changes with every new one. */
    @Column(name = "has_photo", nullable = false)
    private boolean hasPhoto;

    @Column(name = "photo_version", nullable = false)
    private int photoVersion;

    /** A built-in avatar they picked instead of a photo (ProfilePhotoService.AVATARS), or null. */
    @Column(length = 20)
    private String avatar;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public User(String username, String passwordHash, String fullName, Role role, Branch branch) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.branch = branch;
    }
}
