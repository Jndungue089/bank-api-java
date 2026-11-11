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
@Table(name = "savings_plan")
@Getter @Setter @NoArgsConstructor
public class SavingsPlan {
    @Id private UUID accountId;
    private UUID userId;
    private String productCode;
    private String goalName;
    private BigDecimal annualRate;
    private LocalDate maturityDate;
    private String status = "ACTIVE";
    private Instant openedAt = Instant.now();
}
