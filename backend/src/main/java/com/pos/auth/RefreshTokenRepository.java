package com.pos.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken t set t.revoked = true where t.user.id = :userId and t.revoked = false")
    int revokeActive(Long userId);

    @Modifying
    @Query("update RefreshToken t set t.rotatedAt = null where t.user.id = :userId and t.rotatedAt is not null")
    int clearRotation(Long userId);

    /**
     * Signs the user out everywhere and takes away the reuse grace from tokens rotated just before,
     * so none of them can be used again. Returns how many sessions were active.
     */
    default int revokeAllForUser(Long userId) {
        int active = revokeActive(userId);
        clearRotation(userId);
        return active;
    }
}
