package com.sb24.api.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "savings_product")
@Getter @Setter @NoArgsConstructor
public class SavingsProduct {
    @Id private String code;
    private String name;
    private BigDecimal annualRate;
    private BigDecimal minAmount;
    private int termDays;
    private boolean enabled = true;
}
