package com.sb24.api.domain;

import com.sb24.api.crypto.EncryptedStringConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "beneficiary")
@Getter @Setter @NoArgsConstructor
public class Beneficiary {
    @Id private UUID id = UUID.randomUUID();
    private UUID userId;
    private String alias;
    @Convert(converter = EncryptedStringConverter.class) private String name;
    private String iban;
    private String bankCode;
    private Instant createdAt = Instant.now();
    private Instant lastUsedAt;
}
