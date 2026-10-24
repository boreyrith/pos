package com.pos.hold;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface HeldSaleRepository extends JpaRepository<HeldSale, Long> {

    @EntityGraph(attributePaths = {"customer", "createdBy", "lockedBy", "updatedBy"})
    List<HeldSale> findByBranchIdAndStatusInOrderByUpdatedAtDesc(Long branchId, Collection<HoldStatus> statuses);

    /** Locks the hold so two tills can't resume or finish it at the same time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from HeldSale h where h.id = :id")
    Optional<HeldSale> lockById(Long id);

    Optional<HeldSale> findByOrderId(Long orderId);

    List<HeldSale> findByStatusAndUpdatedAtBefore(HoldStatus status, Instant before);

    @Query("select h from HeldSale h where h.status = com.pos.hold.HoldStatus.IN_USE and h.order is null and h.lockedAt < :before")
    List<HeldSale> findAbandonedLocks(Instant before);

    long countByBranchIdAndStatusIn(Long branchId, Collection<HoldStatus> statuses);
}
