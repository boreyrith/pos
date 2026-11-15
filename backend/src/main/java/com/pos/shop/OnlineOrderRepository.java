package com.pos.shop;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.pos.sales.OrderStatus;

import jakarta.persistence.LockModeType;

interface OnlineOrderRepository extends JpaRepository<OnlineOrder, Long> {

    @EntityGraph(attributePaths = {"order", "order.branch", "account"})
    @Query("select oo from OnlineOrder oo where oo.order.orderNumber = :number")
    Optional<OnlineOrder> findByOrderNumber(String number);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select oo from OnlineOrder oo where oo.orderId = :orderId")
    Optional<OnlineOrder> lockByOrderId(Long orderId);

    /** An account's orders, newest first. */
    @EntityGraph(attributePaths = {"order", "order.branch"})
    @Query("select oo from OnlineOrder oo where oo.account.id = :accountId order by oo.createdAt desc")
    Page<OnlineOrder> findByAccount(Long accountId, Pageable pageable);

    /** For staff: by order status (paid, waiting for payment...) and preparation status, in a branch or all. */
    @EntityGraph(attributePaths = {"order", "order.branch"})
    @Query("""
            select oo from OnlineOrder oo
            where oo.order.status in :orderStatuses and oo.status in :statuses
              and (:branchId is null or oo.order.branch.id = :branchId)
            order by oo.createdAt asc
            """)
    List<OnlineOrder> findForStaff(Collection<OrderStatus> orderStatuses, Collection<OnlineOrder.Status> statuses, Long branchId);

    @Query("""
            select count(oo) from OnlineOrder oo
            where oo.order.status = com.pos.sales.OrderStatus.COMPLETED and oo.status = com.pos.shop.OnlineOrder.Status.NEW
              and (:branchId is null or oo.order.branch.id = :branchId)
            """)
    long countNewPaid(Long branchId);

    long countByAccountId(Long accountId);

    /** An account removed by staff: its orders stay, as guest orders. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update OnlineOrder oo set oo.account = null where oo.account.id = :accountId")
    int detachAccount(Long accountId);

    /** Unpaid orders for one phone number: there may only be a few at a time (each holds stock). */
    @Query("""
            select count(oo) from OnlineOrder oo
            where oo.contactPhone = :phone and oo.order.status = com.pos.sales.OrderStatus.PENDING_PAYMENT
            """)
    long countUnpaidForPhone(String phone);
}
