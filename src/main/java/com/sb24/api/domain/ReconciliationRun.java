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
@Table(name = "reconciliation_run")
@Getter @Setter @NoArgsConstructor
public class ReconciliationRun {
    @Id private UUID id = UUID.randomUUID();
    private LocalDate businessDate;
    private String triggeredBy;
    private String status = "RUNNING";
    private int accountsChecked;
    private long entriesChecked;
    private BigDecimal totalDebits = BigDecimal.ZERO;
    private BigDecimal totalCredits = BigDecimal.ZERO;
    private int discrepancies;
    private Instant startedAt = Instant.now();
    private Instant finishedAt;
}
