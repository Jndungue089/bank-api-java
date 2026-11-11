package com.sb24.api.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "direct_debit_mandate")
@Getter @Setter @NoArgsConstructor
public class DirectDebitMandate {
    public enum Status { ACTIVE, PAUSED, CANCELLED }
    public enum Frequency { WEEKLY, MONTHLY, ANNUAL }

    @Id private UUID id = UUID.randomUUID();
    private UUID userId;
    private UUID accountId;
    private String creditorCode;
    private String creditorReference;
    private BigDecimal maxAmount;
    @Enumerated(EnumType.STRING) private Frequency frequency;
    @Enumerated(EnumType.STRING) private Status status = Status.ACTIVE;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
