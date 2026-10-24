package com.pos.inventory;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface StockTransferRepository extends JpaRepository<StockTransfer, Long> {

    @EntityGraph(attributePaths = {"fromStorage", "fromStorage.branch", "toStorage", "toStorage.branch", "createdBy", "closedBy"})
    @Query("""
            select t from StockTransfer t
            where (:status is null or t.status = :status)
              and (:branchId is null or t.fromStorage.branch.id = :branchId or t.toStorage.branch.id = :branchId)
            """)
    Page<StockTransfer> search(TransferStatus status, Long branchId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from StockTransfer t where t.id = :id")
    Optional<StockTransfer> lockById(Long id);

    @Query("select count(t) from StockTransfer t where t.status = com.pos.inventory.TransferStatus.IN_TRANSIT and (:branchId is null or t.toStorage.branch.id = :branchId)")
    long countIncoming(Long branchId);
}
