package com.sb24.api.repo;

import com.sb24.api.domain.Beneficiary;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, UUID> {
    List<Beneficiary> findByUserIdOrderByAliasAsc(UUID userId);
    boolean existsByUserIdAndIban(UUID userId, String iban);
    long countByUserId(UUID userId);
}
