package com.sb24.api.access;

import com.sb24.api.audit.RequestContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The single authorisation decision point. require() = RBAC (does the subject hold the permission?) followed by every
 * ABAC policy (does the context allow it for THIS resource, now, from here?). Refusals throw PolicyDeniedException.
 */
@Service
public class PolicyService {
    private final PermissionService permissions;
    private final List<AbacPolicy> policies;

    public PolicyService(PermissionService permissions, List<AbacPolicy> policies) {
        this.permissions = permissions;
        this.policies = policies;
    }

    /** RBAC only (used by the web interceptor, before any resource is loaded). */
    public void requirePermission(Subject subject, String permission) {
        if (!permissions.has(subject.id(), permission)) {
            throw new PolicyDeniedException(subject, permission, "RBAC", "MISSING_PERMISSION", Resource.none(), false);
        }
    }

    /** RBAC + ABAC against a concrete resource. */
    public void require(Subject subject, String permission, Resource resource) {
        requirePermission(subject, permission);
        AbacPolicy.Request req = new AbacPolicy.Request(subject, permission, resource, Instant.now(), RequestContext.current());
        for (AbacPolicy policy : policies) {
            Optional<AbacPolicy.Decision> d = policy.evaluate(req);
            if (d.isPresent() && !d.get().permit()) {
                throw new PolicyDeniedException(subject, permission, d.get().policy(), d.get().reason(), resource, d.get().maskAsNotFound());
            }
        }
    }

    public boolean hasRole(Subject subject, String role) { return permissions.rolesOf(subject.id()).contains(role); }
}
