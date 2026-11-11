package com.sb24.api.domain;

import com.sb24.api.crypto.EncryptedStringConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "app_user")
@Getter @Setter @NoArgsConstructor
public class AppUser {
    @Id private UUID id = UUID.randomUUID();
    @Convert(converter = EncryptedStringConverter.class) private String fullName;
    private String email;
    @Convert(converter = EncryptedStringConverter.class) private String phone;
    @Convert(converter = EncryptedStringConverter.class) private String nationalId;
    /** HMAC blind index of the national id: uniqueness checks without keeping the id in clear. */
    private String nationalIdIndex;
    private String passwordHash;
    @Enumerated(EnumType.STRING) private Role role = Role.CUSTOMER;
    private boolean enabled = true;
    private int failedAttempts;
    private Instant lockedUntil;
    private UUID blockedBy;
    private String blockedReason;
    private Instant blockedAt;
    private Instant createdAt = Instant.now();

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
