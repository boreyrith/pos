package com.pos.sales;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @EntityGraph(attributePaths = {"cashier", "branch", "customer"})
    @Query("""
            select o from Order o
            where (:branchId is null or o.branch.id = :branchId)
              and (:status is null or o.status = :status)
              and (:from is null or o.createdAt >= :from)
              and (:to is null or o.createdAt < :to)
              and (:customerId is null or o.customer.id = :customerId)
              and (:q is null or o.orderNumber like concat('%', :q, '%') or o.customerName like concat('%', :q, '%')
                   or o.customer.phone like concat('%', :q, '%') or o.customer.code like concat('%', :q, '%'))
            """)
    Page<Order> search(Long branchId, OrderStatus status, Instant from, Instant to, String q, Long customerId, Pageable pageable);

    @EntityGraph(attributePaths = {"cashier", "items", "branch", "storage", "customer"})
    Optional<Order> findWithItemsById(Long id);

    /** Locks the order row so concurrent pay/cancel/refund calls are applied one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> lockById(Long id);
}
