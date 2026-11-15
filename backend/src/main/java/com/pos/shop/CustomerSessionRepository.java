package com.pos.shop;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface CustomerSessionRepository extends JpaRepository<CustomerSession, String> {

    @EntityGraph(attributePaths = {"account", "account.customer"})
    Optional<CustomerSession> findWithAccountByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from CustomerSession s where s.account.id = :accountId")
    int deleteByAccount(Long accountId);

    /** Housekeeping: sessions ended or expired long enough ago. */
    @Modifying
    @Query("delete from CustomerSession s where s.expiresAt < :before or (s.endedAt is not null and s.endedAt < :before)")
    int deleteOld(Instant before);
}
