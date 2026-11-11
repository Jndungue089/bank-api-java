package com.sb24.api.repo;

import com.sb24.api.domain.RefreshToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") java.time.Instant cutoff);

    @Modifying
    @Query("update RefreshToken t set t.revoked = true where t.familyId = :familyId")
    void revokeFamily(@Param("familyId") UUID familyId);
}
