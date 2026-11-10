package com.pos.marketing;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    /** Turned on and running without a code; dates, hours and limits are checked by the caller. */
    List<Promotion> findByActiveTrueAndRequiresCodeFalse();

    List<Promotion> findAllByOrderByActiveDescCreatedAtDesc();

    /** Locked while a sale counts its use, so two tills can't both take the last one. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Promotion p where p.id in :ids order by p.id")
    List<Promotion> lockAll(Collection<Long> ids);
}
