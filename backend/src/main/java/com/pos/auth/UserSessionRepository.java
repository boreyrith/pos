package com.pos.auth;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserSessionRepository extends JpaRepository<UserSession, String> {

    @Query("select s from UserSession s where s.user.id = :userId and s.endedAt is null and s.expiresAt > :now order by s.lastUsedAt desc")
    List<UserSession> findActiveByUser(Long userId, Instant now);

    @Query("select s from UserSession s join fetch s.user u join fetch u.role where s.endedAt is null and s.expiresAt > :now order by s.lastUsedAt desc")
    List<UserSession> findAllActive(Instant now);
}
