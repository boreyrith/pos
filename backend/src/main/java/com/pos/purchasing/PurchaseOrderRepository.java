package com.pos.purchasing;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    @Query("""
            select p from PurchaseOrder p join fetch p.vendor join fetch p.branch
            where (:branchId is null or p.branch.id = :branchId)
              and (:status is null or p.status = :status)
              and (:vendorId is null or p.vendor.id = :vendorId)
              and (:q is null or p.poNumber like concat('%', :q, '%') or p.vendor.name like concat('%', :q, '%')
                   or p.vendorRef like concat('%', :q, '%'))
            order by p.createdAt desc
            """)
    Page<PurchaseOrder> search(Long branchId, PurchaseStatus status, Long vendorId, String q, Pageable pageable);

    /** Locks the order so concurrent deliveries are applied one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PurchaseOrder p where p.id = :id")
    Optional<PurchaseOrder> lockById(Long id);

    long countByVendorIdAndStatusIn(Long vendorId, java.util.Collection<PurchaseStatus> statuses);
}
