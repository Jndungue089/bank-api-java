package com.sb24.api.repo;

import com.sb24.api.domain.DirectDebitCollection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DirectDebitCollectionRepository extends JpaRepository<DirectDebitCollection, UUID> {
    Optional<DirectDebitCollection> findByPartnerIdAndExternalRef(UUID partnerId, String externalRef);
    List<DirectDebitCollection> findByMandateIdOrderByCreatedAtDesc(UUID mandateId);

    @Query("select count(c) from DirectDebitCollection c where c.mandateId = :mandateId and c.id <> :self and c.status = com.sb24.api.domain.DirectDebitCollection$Status.PAID and c.dueDate >= :from and c.dueDate < :to")
    long paidInPeriod(@Param("mandateId") UUID mandateId, @Param("self") UUID self, @Param("from") java.time.LocalDate from, @Param("to") java.time.LocalDate to);
}
