package com.sb24.api.repo;

import com.sb24.api.domain.KnownDevice;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnownDeviceRepository extends JpaRepository<KnownDevice, UUID> {
    Optional<KnownDevice> findByUserIdAndDeviceKey(UUID userId, String deviceKey);
    long countByUserId(UUID userId);
}
