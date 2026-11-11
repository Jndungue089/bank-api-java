package com.sb24.api.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "refresh_token")
@Getter @Setter @NoArgsConstructor
public class RefreshToken {
    @Id private UUID id = UUID.randomUUID();
    private UUID userId;
    private UUID familyId;
    private String tokenHash;
    private Instant expiresAt;
    private boolean used;
    private boolean revoked;
    private Instant createdAt = Instant.now();
}
