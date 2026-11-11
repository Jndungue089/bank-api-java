package com.sb24.api.audit;

import com.sb24.api.domain.AuditLog;
import java.util.UUID;

/** Request attribute set once the JWT is validated, so the outer trail filter can attribute the request. */
public record TrailActor(AuditLog.ActorType type, UUID id) {
    public static final String ATTR = "sb24.trail.actor";
}
