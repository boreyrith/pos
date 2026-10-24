package com.pos.sales;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderReturnRepository extends JpaRepository<OrderReturn, Long> {

    /** Returns and refunds, newest first. {@code q} matches the return or order number, or the customer. */
    @EntityGraph(attributePaths = {"order", "order.branch", "order.customer", "createdBy"})
    @Query("""
            select r from OrderReturn r
            where (:branchId is null or r.order.branch.id = :branchId)
              and (:from is null or r.createdAt >= :from)
              and (:to is null or r.createdAt < :to)
              and (:customerId is null or r.order.customer.id = :customerId)
              and (:q is null or r.returnNumber like concat('%', :q, '%') or r.order.orderNumber like concat('%', :q, '%')
                   or r.order.customerName like concat('%', :q, '%') or r.reason like concat('%', :q, '%'))
            order by r.createdAt desc, r.id desc
            """)
    Page<OrderReturn> search(Long branchId, Instant from, Instant to, Long customerId, String q, Pageable pageable);
}
