package com.sb24.api.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "idempotency_record")
@Getter @Setter @NoArgsConstructor
public class IdempotencyRecord {
    @Id private UUID id = UUID.randomUUID();
    private UUID userId;
    @Column(name = "idem_key") private String key;
    private String requestHash;
    @Column(columnDefinition = "TEXT") private String responseBody;
    private Instant createdAt = Instant.now();
}
