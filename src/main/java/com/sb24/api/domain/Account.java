package com.sb24.api.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "account")
@Getter @Setter @NoArgsConstructor
public class Account {
    @Id private UUID id = UUID.randomUUID();
    private UUID ownerId;
    private String iban;
    /** Only for SETTLEMENT accounts: CLEARING, PAYMENTS or FEES. */
    private String purpose;
    @Enumerated(EnumType.STRING) private AccountType type = AccountType.CURRENT;
    private String currency = "AOA";
    /** Saldo contabilístico: what the ledger says. */
    private BigDecimal balance = BigDecimal.ZERO.setScale(2);
    /** Cativo: sum of ACTIVE holds. */
    private BigDecimal held = BigDecimal.ZERO.setScale(2);
    private String status = "ACTIVE";
    /** Hash-chain head of this account's ledger (updated under the row lock). */
    private long ledgerSeq;
    private String lastHash;
    @Version private long version;
    private Instant createdAt = Instant.now();

    /** Saldo disponível = contabilístico - cativo. Only this is spendable. */
    public BigDecimal available() { return balance.subtract(held); }

    public boolean isActive() { return "ACTIVE".equals(status); }
}
