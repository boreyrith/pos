package com.pos.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One sign-in on one device, across all its refresh tokens. See V30__user_sessions.sql. */
@Entity
@Table(name = "user_sessions")
@Getter
@Setter
@NoArgsConstructor
public class UserSession {

    /** Why a session ended. */
    public enum EndReason { SIGNED_OUT, ENDED, ADMIN, PASSWORD_CHANGED, ACCOUNT_CHANGED, TOKEN_REUSED }

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", updatable = false)
    private User user;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** "Remember me" at sign-in: the refresh cookie outlives the browser; otherwise it ends with it. */
    @Column(nullable = false)
    private boolean remember = true;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "ended_at")
    private Instant endedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ended_by")
    private User endedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 20)
    private EndReason endReason;

    public UserSession(String id, User user, Instant expiresAt, ClientInfo client, boolean remember) {
        this.id = id;
        this.user = user;
        this.expiresAt = expiresAt;
        this.remember = remember;
        seenFrom(client);
    }

    public boolean isActive(Instant now) {
        return endedAt == null && expiresAt.isAfter(now);
    }

    /** Records where and when it was last used. */
    public void seenFrom(ClientInfo client) {
        lastUsedAt = Instant.now();
        if (client != null) {
            ipAddress = client.ip();
            userAgent = client.userAgent();
        }
    }
}
