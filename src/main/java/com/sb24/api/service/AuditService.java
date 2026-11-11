package com.sb24.api.service;

import com.sb24.api.audit.RequestContext;
import com.sb24.api.crypto.FieldCipher;
import com.sb24.api.domain.AuditLog;
import com.sb24.api.domain.AuditLog.ActorType;
import com.sb24.api.repo.AuditLogRepository;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single writer of the audit trail. Every row gets the request context (IP, device, user agent, request id) and an
 * HMAC over its content, so edits to the table are detectable. Joins the caller's transaction on purpose
 * (REQUIRES_NEW would borrow a second pool connection while holding the first). Money services therefore audit
 * denials AFTER their transaction ends. Never pass secrets in `detail`.
 */
@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger("AUDIT");
    private final AuditLogRepository repo;
    private final FieldCipher cipher;

    public AuditService(AuditLogRepository repo, FieldCipher cipher) {
        this.repo = repo;
        this.cipher = cipher;
    }

    /** Customer-side event. Actor is ANONYMOUS without a user id, SYSTEM when there is no HTTP request (workers, crons). */
    @Transactional
    public void record(UUID userId, String action, String outcome, String ip, String detail) {
        customer(userId, action, outcome, null, null, detail);
    }

    @Transactional
    public void customer(UUID userId, String action, String outcome, String resourceType, String resourceId, String detail) {
        ActorType type = RequestContext.current() == null ? ActorType.SYSTEM : userId == null ? ActorType.ANONYMOUS : ActorType.CUSTOMER;
        save(type, userId, action, outcome, resourceType, resourceId, null, null, null, detail);
    }

    /** Generic event for any actor type (partners, anonymous callers). */
    @Transactional
    public void event(ActorType type, UUID actorId, String action, String outcome, String resourceType, String resourceId, String detail) {
        save(type, actorId, action, outcome, resourceType, resourceId, null, null, null, detail);
    }

    /** Back-office event: always attributed to the admin, with the target resource. */
    @Transactional
    public void admin(UUID adminId, String action, String outcome, String resourceType, String resourceId, String detail) {
        save(adminId == null ? ActorType.ANONYMOUS : ActorType.ADMIN, adminId, action, outcome, resourceType, resourceId, null, null, null, detail);
    }

    /** Written by the exception handler AFTER the failed transaction is gone, so the refusal itself is never rolled back. */
    @Transactional
    public void denied(com.sb24.api.access.Subject subject, String permission, String policy, String reason, com.sb24.api.access.Resource resource) {
        ActorType type = subject == null ? ActorType.ANONYMOUS : switch (subject.realm()) {
            case "admin" -> ActorType.ADMIN;
            case "partner" -> ActorType.PARTNER;
            case "customer" -> ActorType.CUSTOMER;
            default -> ActorType.ANONYMOUS;
        };
        save(type, subject == null ? null : subject.id(), "ACCESS_DENIED", "DENIED", resource == null ? null : resource.type(),
                resource == null ? null : resource.id(), null, null, null, "permission=" + permission + " policy=" + policy + " reason=" + reason);
    }

    @Transactional
    public void system(String action, String outcome, String resourceType, String resourceId, String detail) {
        save(ActorType.SYSTEM, null, action, outcome, resourceType, resourceId, null, null, null, detail);
    }

    @Transactional
    public void request(ActorType type, UUID actorId, String method, String path, int status, String outcome, String detail) {
        save(type, actorId, "HTTP_REQUEST", outcome, null, null, method, path, status, detail);
    }

    private void save(ActorType type, UUID actorId, String action, String outcome, String resourceType, String resourceId,
                      String method, String path, Integer status, String detail) {
        RequestContext ctx = RequestContext.current();
        AuditLog e = new AuditLog();
        e.setActorType(type);
        e.setActorId(actorId);
        e.setAction(action);
        e.setOutcome(outcome);
        e.setResourceType(resourceType);
        e.setResourceId(resourceId);
        e.setHttpMethod(method);
        e.setPath(path == null ? null : path.substring(0, Math.min(path.length(), 200)));
        e.setHttpStatus(status);
        if (ctx != null) {
            e.setIp(ctx.ip());
            e.setDeviceId(ctx.deviceId());
            e.setUserAgent(ctx.userAgent());
            e.setRequestId(ctx.requestId());
        }
        e.setDetail(detail == null ? null : detail.substring(0, Math.min(detail.length(), 500)));
        e.setCreatedAt(java.time.Instant.now().truncatedTo(ChronoUnit.MILLIS));
        e.setMac(mac(e));
        repo.save(e);
        if (!"HTTP_REQUEST".equals(action)) {
            log.info("actor={}:{} action={} outcome={} resource={}:{} ip={}", type, actorId, action, outcome, resourceType, resourceId, e.getIp());
        }
    }

    public String mac(AuditLog e) {
        return cipher.auditMac(String.join("|", e.getId().toString(), String.valueOf(e.getActorType()), String.valueOf(e.getActorId()),
                e.getAction(), nz(e.getResourceType()), nz(e.getResourceId()), e.getOutcome(), nz(e.getHttpMethod()), nz(e.getPath()),
                String.valueOf(e.getHttpStatus()), nz(e.getIp()), nz(e.getDeviceId()), nz(e.getUserAgent()), nz(e.getRequestId()),
                nz(e.getDetail()), Long.toString(e.getCreatedAt().toEpochMilli())));
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
