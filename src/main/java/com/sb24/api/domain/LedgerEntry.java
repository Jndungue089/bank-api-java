package com.sb24.api.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Immutable double-entry style ledger line. Every movement produces a DEBIT and a matching CREDIT sharing transferRef. */
@Entity
@Table(name = "ledger_entry")
@Getter @Setter @NoArgsConstructor
public class LedgerEntry {
    public enum Kind { TRANSFER, PAYMENT, FEE, OPENING, SAVINGS, INTEREST, DIRECT_DEBIT }
    public enum Direction { DEBIT, CREDIT }

    @Id private UUID id = UUID.randomUUID();
    private UUID accountId;
    private long seq;
    private UUID transferRef;
    @Enumerated(EnumType.STRING) private Kind kind;
    @Enumerated(EnumType.STRING) private Direction direction;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private String counterpartyIban;
    private String description;
    private String prevHash;
    private String hash;
    private Instant createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
}
