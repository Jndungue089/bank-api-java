package com.sb24.api.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "savings_interest")
@Getter @Setter @NoArgsConstructor
public class SavingsInterest {
    @Id private UUID id = UUID.randomUUID();
    private UUID accountId;
    private LocalDate accrualDate;
    private BigDecimal amount;
}
