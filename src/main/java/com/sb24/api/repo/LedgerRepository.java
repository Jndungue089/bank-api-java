package com.sb24.api.repo;

import com.sb24.api.domain.LedgerEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerRepository extends JpaRepository<LedgerEntry, UUID> {
    java.util.List<LedgerEntry> findByAccountIdOrderBySeqAsc(UUID accountId);

    java.util.List<LedgerEntry> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByAccountIdAscSeqAsc(Instant from, Instant to);

    java.util.Optional<LedgerEntry> findFirstByAccountIdAndCreatedAtLessThanOrderBySeqDesc(UUID accountId, Instant before);

    boolean existsByTransferRef(UUID transferRef);

    Page<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    @Query("select coalesce(sum(e.amount),0) from LedgerEntry e where e.accountId = :accountId "
         + "and e.direction = com.sb24.api.domain.LedgerEntry$Direction.DEBIT and e.createdAt >= :since")
    BigDecimal debitedSince(@Param("accountId") UUID accountId, @Param("since") Instant since);
}
