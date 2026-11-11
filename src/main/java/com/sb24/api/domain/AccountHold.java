package com.sb24.api.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "account_hold")
@Getter @Setter @NoArgsConstructor
public class AccountHold {
    public enum Status { ACTIVE, CAPTURED, RELEASED }

    @Id private UUID id = UUID.randomUUID();
    private UUID accountId;
    private UUID ref;
    private BigDecimal amount;
    @Enumerated(EnumType.STRING) private Status status = Status.ACTIVE;
    private Instant createdAt = Instant.now();
    private Instant closedAt;
}
