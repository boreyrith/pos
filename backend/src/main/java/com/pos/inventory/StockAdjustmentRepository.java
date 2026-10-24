package com.pos.inventory;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, Long> {

    @EntityGraph(attributePaths = {"storage", "storage.branch", "createdBy"})
    @Query("""
            select a from StockAdjustment a
            where (:branchId is null or a.storage.branch.id = :branchId)
              and (:storageId is null or a.storage.id = :storageId)
              and (:kind is null or a.kind = :kind)
              and (:from is null or a.createdAt >= :from)
              and (:to is null or a.createdAt < :to)
              and (:reason is null or exists (select l from StockAdjustmentLine l where l.adjustment = a and l.reason = :reason))
            """)
    Page<StockAdjustment> search(Long branchId, Long storageId, AdjustmentKind kind, AdjustmentReason reason,
                                 Instant from, Instant to, Pageable pageable);
}
