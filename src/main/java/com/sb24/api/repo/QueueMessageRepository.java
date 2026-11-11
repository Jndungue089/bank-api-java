package com.sb24.api.repo;

import com.sb24.api.domain.QueueMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QueueMessageRepository extends JpaRepository<QueueMessage, UUID> {
    boolean existsByDedupKey(String dedupKey);
    long countByStatus(QueueMessage.Status status);
    List<QueueMessage> findByStatusOrderByCreatedAtDesc(QueueMessage.Status status, Pageable pageable);

    /** Ready = pending and due, or processing with an expired lease (worker died). */
    @Query("select m.id from QueueMessage m where (m.status = com.sb24.api.domain.QueueMessage$Status.PENDING and m.nextAttemptAt <= :now) "
         + "or (m.status = com.sb24.api.domain.QueueMessage$Status.PROCESSING and m.lockedUntil < :now) order by m.nextAttemptAt")
    List<UUID> findReady(@Param("now") Instant now, Pageable pageable);

    /** Atomic claim: only one worker (across all instances) gets updateCount == 1. */
    @Modifying
    @Query("update QueueMessage m set m.status = com.sb24.api.domain.QueueMessage$Status.PROCESSING, m.lockedBy = :worker, m.lockedUntil = :until, m.attempts = m.attempts + 1 "
         + "where m.id = :id and ((m.status = com.sb24.api.domain.QueueMessage$Status.PENDING and m.nextAttemptAt <= :now) "
         + "or (m.status = com.sb24.api.domain.QueueMessage$Status.PROCESSING and m.lockedUntil < :now))")
    int claim(@Param("id") UUID id, @Param("worker") String worker, @Param("until") Instant until, @Param("now") Instant now);

    @Modifying
    @Query("delete from QueueMessage m where m.status = com.sb24.api.domain.QueueMessage$Status.DONE and m.processedAt < :cutoff")
    int deleteDoneBefore(@Param("cutoff") Instant cutoff);
}
