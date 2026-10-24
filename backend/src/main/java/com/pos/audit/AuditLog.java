package com.pos.audit;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One audit entry. Written by {@link AuditService}; never changed afterwards. */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "user_id", updatable = false)
    private Long userId;

    @Column(length = 50, updatable = false)
    private String username;

    @Column(name = "branch_id", updatable = false)
    private Long branchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private AuditAction.Category category;

    @Column(name = "entity_type", length = 30, updatable = false)
    private String entityType;

    @Column(name = "entity_id", length = 40, updatable = false)
    private String entityId;

    @Column(nullable = false, length = 500, updatable = false)
    private String summary;

    @Column(columnDefinition = "TEXT", updatable = false)
    private String details;

    @Column(length = 64, updatable = false)
    private String ip;

    @Column(name = "user_agent", length = 255, updatable = false)
    private String userAgent;
}
