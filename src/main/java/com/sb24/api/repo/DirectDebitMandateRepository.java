package com.sb24.api.repo;

import com.sb24.api.domain.DirectDebitMandate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DirectDebitMandateRepository extends JpaRepository<DirectDebitMandate, UUID> {
    List<DirectDebitMandate> findByUserIdOrderByCreatedAtDesc(UUID userId);
    Optional<DirectDebitMandate> findByCreditorCodeAndCreditorReference(String creditorCode, String creditorReference);
}
