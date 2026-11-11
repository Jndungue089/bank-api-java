package com.sb24.api.repo;

import com.sb24.api.domain.IdempotencyRecord;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, UUID> {
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from IdempotencyRecord r where r.createdAt < :cutoff")
    int deleteOlderThan(@org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff);

    Optional<IdempotencyRecord> findByUserIdAndKey(UUID userId, String key);
}
