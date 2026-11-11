package com.sb24.api.repo;

import com.sb24.api.domain.AccountHold;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountHoldRepository extends JpaRepository<AccountHold, UUID> {
    Optional<AccountHold> findByRefAndStatus(UUID ref, AccountHold.Status status);

    @Query("select coalesce(sum(h.amount),0) from AccountHold h where h.accountId = :accountId and h.status = com.sb24.api.domain.AccountHold$Status.ACTIVE")
    BigDecimal activeTotal(@Param("accountId") UUID accountId);
}
