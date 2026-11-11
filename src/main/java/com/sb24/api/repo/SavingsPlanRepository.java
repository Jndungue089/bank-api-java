package com.sb24.api.repo;

import com.sb24.api.domain.SavingsPlan;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavingsPlanRepository extends JpaRepository<SavingsPlan, UUID> {
    List<SavingsPlan> findByUserIdOrderByOpenedAtAsc(UUID userId);
    List<SavingsPlan> findByStatus(String status);
}
