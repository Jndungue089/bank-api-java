package com.sb24.api.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "known_device")
@Getter @Setter @NoArgsConstructor
public class KnownDevice {
    @Id private UUID id = UUID.randomUUID();
    private UUID userId;
    private String deviceKey;
    private String userAgent;
    private String firstIp;
    private Instant firstSeen = Instant.now();
    private Instant lastSeen = Instant.now();
}
