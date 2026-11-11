package com.sb24.api.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "reconciliation_item")
@Getter @Setter @NoArgsConstructor
public class ReconciliationItem {
    @Id private UUID id = UUID.randomUUID();
    private UUID runId;
    private String severity;
    private String type;
    private UUID accountId;
    private BigDecimal expected;
    private BigDecimal actual;
    private String detail;
}
