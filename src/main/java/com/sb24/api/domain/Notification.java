package com.sb24.api.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notification")
@Getter @Setter @NoArgsConstructor
public class Notification {
    @Id private UUID id = UUID.randomUUID();
    private UUID userId;
    private String channel = "SMS";
    private String message;
    private String status = "SENT";
    private Instant createdAt = Instant.now();
}
