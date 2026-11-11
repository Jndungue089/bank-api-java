package com.sb24.api.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "daily_snapshot")
@Getter @Setter @NoArgsConstructor
public class DailySnapshot {
    @Id private UUID id = UUID.randomUUID();
    private UUID accountId;
    private LocalDate businessDate;
    private BigDecimal openingBalance;
    private BigDecimal totalDebits;
    private BigDecimal totalCredits;
    private BigDecimal closingBalance;
    private int entries;
}
