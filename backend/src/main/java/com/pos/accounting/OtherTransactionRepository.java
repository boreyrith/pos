package com.pos.accounting;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface OtherTransactionRepository extends JpaRepository<OtherTransaction, Long> {

    @Query("""
            select t from OtherTransaction t join fetch t.account join fetch t.moneyAccount join fetch t.branch
            where t.date between :from and :to
              and (:kind is null or t.kind = :kind)
              and (:branchId is null or t.branch.id = :branchId)
              and (:accountId is null or t.account.id = :accountId)
              and (:q is null or t.number like concat('%', :q, '%') or t.party like concat('%', :q, '%')
                   or t.reference like concat('%', :q, '%') or t.note like concat('%', :q, '%'))
            order by t.date desc, t.id desc
            """)
    Page<OtherTransaction> search(LocalDate from, LocalDate to, OtherTransaction.Kind kind, Long branchId, Long accountId, String q,
                                  Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from OtherTransaction t where t.id = :id")
    Optional<OtherTransaction> lockById(Long id);
}
