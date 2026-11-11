package com.sb24.api.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "admin_user")
@Getter @Setter @NoArgsConstructor
public class AdminUser {
    @Id private UUID id = UUID.randomUUID();
    private String email;
    private String fullName;
    private String passwordHash;
    private boolean enabled = true;
    private int failedAttempts;
    private Instant lockedUntil;
    private UUID createdBy;
    private Instant lastLoginAt;
    private String lastLoginIp;
    private Instant createdAt = Instant.now();

    public boolean isLocked(Instant now) { return lockedUntil != null && lockedUntil.isAfter(now); }
}
