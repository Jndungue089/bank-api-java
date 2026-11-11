package com.sb24.api.access;

import com.sb24.api.service.ApiException;
import org.springframework.http.HttpStatus;

/** Thrown when RBAC (missing permission) or an ABAC policy refuses an operation. Audited centrally by the exception handler. */
public class PolicyDeniedException extends ApiException {
    private final Subject subject;
    private final String permission;
    private final String policy;
    private final String reason;
    private final Resource resource;

    public PolicyDeniedException(Subject subject, String permission, String policy, String reason, Resource resource, boolean maskAsNotFound) {
        super(maskAsNotFound ? HttpStatus.NOT_FOUND : HttpStatus.FORBIDDEN,
                maskAsNotFound ? "NOT_FOUND" : reason,
                maskAsNotFound ? "Recurso não encontrado." : "Operação não permitida (" + reason + ").");
        this.subject = subject;
        this.permission = permission;
        this.policy = policy;
        this.reason = reason;
        this.resource = resource;
    }

    public Subject subject() { return subject; }
    public String permission() { return permission; }
    public String policy() { return policy; }
    public String reason() { return reason; }
    public Resource resource() { return resource; }
}
