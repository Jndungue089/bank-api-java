package com.sb24.api.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "queue_message")
@Getter @Setter @NoArgsConstructor
public class QueueMessage {
    public enum Status { PENDING, PROCESSING, DONE, DEAD }

    @Id private UUID id = UUID.randomUUID();
    private String queue;
    @Column(columnDefinition = "TEXT") private String payload;
    private String dedupKey;
    @Enumerated(EnumType.STRING) private Status status = Status.PENDING;
    private int attempts;
    private int maxAttempts;
    private Instant nextAttemptAt = Instant.now();
    private String lockedBy;
    private Instant lockedUntil;
    private String lastError;
    private Instant createdAt = Instant.now();
    private Instant processedAt;
}
