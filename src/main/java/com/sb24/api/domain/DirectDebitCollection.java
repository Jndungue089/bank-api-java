package com.sb24.api.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "direct_debit_collection")
@Getter @Setter @NoArgsConstructor
public class DirectDebitCollection {
    public enum Status { PENDING, PAID, RETURNED, REJECTED }

    @Id private UUID id = UUID.randomUUID();
    private UUID mandateId;
    private UUID partnerId;
    private String externalRef;
    private BigDecimal amount;
    private LocalDate dueDate;
    @Enumerated(EnumType.STRING) private Status status = Status.PENDING;
    private String reason;
    private Instant createdAt = Instant.now();
    private Instant processedAt;
}
