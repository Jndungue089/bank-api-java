package com.sb24.api.repo;

import com.sb24.api.domain.Account;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    List<Account> findByOwnerIdOrderByCreatedAtAsc(UUID ownerId);
    Optional<Account> findByIdAndOwnerId(UUID id, UUID ownerId);
    Optional<Account> findByIban(String iban);
    boolean existsByIban(String iban);

    @Query("select a.id from Account a where a.iban = :iban")
    Optional<UUID> findIdByIban(@Param("iban") String iban);

    @Query("select a.id from Account a where a.purpose = :purpose")
    Optional<UUID> findIdByPurpose(@Param("purpose") String purpose);

    @Query("select coalesce(sum(a.held),0) from Account a")
    java.math.BigDecimal totalHeld();

    @Query("select coalesce(sum(a.balance),0) from Account a")
    java.math.BigDecimal totalBalance();

    /** Row locks, always acquired in ascending id order to rule out deadlocks. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> lockAllById(@Param("ids") List<UUID> ids);
}
