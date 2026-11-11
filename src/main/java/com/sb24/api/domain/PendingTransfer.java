package com.sb24.api.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "pending_transfer")
@Getter @Setter @NoArgsConstructor
public class PendingTransfer {
    public enum Status { PENDING, SETTLED, REJECTED }

    @Id private UUID id = UUID.randomUUID();
    private UUID userId;
    private UUID accountId;
    private String toIban;
    private BigDecimal amount;
    private BigDecimal fee;
    private String description;
    @Enumerated(EnumType.STRING) private Status status = Status.PENDING;
    private String reason;
    private Instant createdAt = Instant.now();
    private Instant settledAt;
}
