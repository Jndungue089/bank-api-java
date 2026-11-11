package com.sb24.api.repo;

import com.sb24.api.domain.Partner;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerRepository extends JpaRepository<Partner, UUID> {
    Optional<Partner> findByClientId(String clientId);
}
