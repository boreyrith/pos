package com.pos.accounting;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    boolean existsBySourceKey(String sourceKey);

    Optional<JournalEntry> findBySourceKey(String sourceKey);

    Optional<JournalEntry> findByReversalOfId(Long id);

    @Query("""
            select e from JournalEntry e left join e.branch b
            where e.entryDate between :from and :to
              and (:branchId is null or b.id = :branchId)
              and (:source is null or e.source = :source)
              and (:accountId is null or exists (select 1 from JournalLine l where l.entry = e and l.account.id = :accountId))
              and (:q is null or e.entryNumber like concat('%', :q, '%') or e.reference like concat('%', :q, '%')
                   or e.memo like concat('%', :q, '%'))
            order by e.entryDate desc, e.id desc
            """)
    Page<JournalEntry> search(LocalDate from, LocalDate to, Long branchId, JournalSource source, Long accountId, String q,
                              Pageable pageable);
}
