package com.sb24.api.repo;

import com.sb24.api.domain.ReconciliationItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationItemRepository extends JpaRepository<ReconciliationItem, UUID> {
    List<ReconciliationItem> findByRunId(UUID runId);
}
