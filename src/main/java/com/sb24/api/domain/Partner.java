package com.sb24.api.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "partner")
@Getter @Setter @NoArgsConstructor
public class Partner {
    @Id private UUID id = UUID.randomUUID();
    private String creditorCode;
    private String name;
    private String clientId;
    private String secretHash;
    private boolean enabled = true;
    private int failedAttempts;
    private Instant lockedUntil;
    private Instant createdAt = Instant.now();

    public boolean isLocked(Instant now) { return lockedUntil != null && lockedUntil.isAfter(now); }
}
