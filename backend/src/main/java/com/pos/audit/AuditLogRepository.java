package com.pos.audit;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("""
            select a from AuditLog a
            where (:userId is null or a.userId = :userId)
              and (:category is null or a.category = :category)
              and (:action is null or a.action = :action)
              and (:entityType is null or a.entityType = :entityType)
              and (:entityId is null or a.entityId = :entityId)
              and (:from is null or a.createdAt >= :from)
              and (:to is null or a.createdAt < :to)
              and (:q is null or lower(a.summary) like lower(concat('%', :q, '%'))
                   or lower(a.username) like lower(concat('%', :q, '%')))
            """)
    Page<AuditLog> search(Long userId, AuditAction.Category category, AuditAction action, String entityType,
                          String entityId, Instant from, Instant to, String q, Pageable pageable);
}
