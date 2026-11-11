package com.sb24.api.repo;

import com.sb24.api.domain.SavingsInterest;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavingsInterestRepository extends JpaRepository<SavingsInterest, UUID> {
    boolean existsByAccountIdAndAccrualDate(UUID accountId, LocalDate date);
}
