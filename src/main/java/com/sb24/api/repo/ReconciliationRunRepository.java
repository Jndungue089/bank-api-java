package com.sb24.api.repo;

import com.sb24.api.domain.ReconciliationRun;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {
    Page<ReconciliationRun> findAllByOrderByStartedAtDesc(Pageable pageable);
    boolean existsByBusinessDate(LocalDate date);
}
