package com.sb24.api.repo;

import com.sb24.api.domain.PendingTransfer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingTransferRepository extends JpaRepository<PendingTransfer, UUID> {
    Page<PendingTransfer> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
    List<PendingTransfer> findByStatusAndCreatedAtBefore(PendingTransfer.Status status, Instant before);
    List<PendingTransfer> findByStatusAndSettledAtGreaterThanEqualAndSettledAtLessThan(PendingTransfer.Status status, Instant from, Instant to);
}
