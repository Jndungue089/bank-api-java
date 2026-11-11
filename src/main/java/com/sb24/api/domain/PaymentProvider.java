package com.sb24.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "payment_provider")
@Getter @Setter @NoArgsConstructor
public class PaymentProvider {
    @Id private String code;
    private String name;
    private String referencePattern;
    private String referenceHint;
    private boolean directDebit = true;
    private boolean enabled = true;
}
