package com.pos.loyalty;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LoyaltyTransactionRepository extends JpaRepository<LoyaltyTransaction, Long> {

    @Query("select t from LoyaltyTransaction t left join fetch t.createdBy where t.customer.id = :customerId order by t.id desc")
    Page<LoyaltyTransaction> findByCustomer(Long customerId, Pageable pageable);

    @Query("select t from LoyaltyTransaction t join fetch t.customer left join fetch t.createdBy order by t.id desc")
    Page<LoyaltyTransaction> recent(Pageable pageable);
}
