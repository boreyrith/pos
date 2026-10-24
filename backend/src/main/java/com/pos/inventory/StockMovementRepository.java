package com.pos.inventory;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    @EntityGraph(attributePaths = {"offering", "offering.unit", "storage", "storage.branch", "user", "adjustment"})
    @Query("""
            select m from StockMovement m
            where (:offeringId is null or m.offering.id = :offeringId)
              and (:storageId is null or m.storage.id = :storageId)
              and (:branchId is null or m.storage.branch.id = :branchId)
              and (:type is null or m.type = :type)
              and (:from is null or m.createdAt >= :from)
              and (:to is null or m.createdAt < :to)
            """)
    Page<StockMovement> search(Long offeringId, Long storageId, Long branchId, MovementType type, Instant from,
                               Instant to, Pageable pageable);
}
