package com.sb24.api.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "audit_log")
@Getter @Setter @NoArgsConstructor
public class AuditLog {
    public enum ActorType { CUSTOMER, ADMIN, PARTNER, SYSTEM, ANONYMOUS }

    @Id private UUID id = UUID.randomUUID();
    @Enumerated(EnumType.STRING) private ActorType actorType;
    private UUID actorId;
    private String action;
    private String resourceType;
    private String resourceId;
    private String outcome;
    private String httpMethod;
    private String path;
    private Integer httpStatus;
    private String ip;
    private String deviceId;
    private String userAgent;
    private String requestId;
    private String detail;
    private Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private String mac;
}
