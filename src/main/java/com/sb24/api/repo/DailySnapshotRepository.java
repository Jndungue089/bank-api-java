package com.sb24.api.repo;

import com.sb24.api.domain.DailySnapshot;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailySnapshotRepository extends JpaRepository<DailySnapshot, UUID> {
    Optional<DailySnapshot> findByAccountIdAndBusinessDate(UUID accountId, LocalDate date);
    List<DailySnapshot> findByBusinessDate(LocalDate date);
}
