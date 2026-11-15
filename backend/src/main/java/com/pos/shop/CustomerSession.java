package com.pos.shop;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A browser signed in to the shop. The token travels in an HttpOnly cookie; only its hash is kept. */
@Entity
@Table(name = "customer_sessions")
@Getter
@Setter
@NoArgsConstructor
public class CustomerSession {

    @Id
    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private CustomerAccount account;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    CustomerSession(String tokenHash, CustomerAccount account, Instant expiresAt, String ip, String agent) {
        this.tokenHash = tokenHash;
        this.account = account;
        this.expiresAt = expiresAt;
        this.ipAddress = ip;
        this.userAgent = agent;
    }

    boolean isActive(Instant now) {
        return endedAt == null && expiresAt.isAfter(now);
    }
}
