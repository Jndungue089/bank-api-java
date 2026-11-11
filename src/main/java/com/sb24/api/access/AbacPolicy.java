package com.sb24.api.access;

import com.sb24.api.audit.RequestContext;
import java.time.Instant;
import java.util.Optional;

/** Attribute-based rule over subject, action, resource and environment. Empty = not applicable. Any DENY wins (deny-overrides). */
public interface AbacPolicy {
    record Request(Subject subject, String permission, Resource resource, Instant now, RequestContext env) {}

    record Decision(boolean permit, String policy, String reason, boolean maskAsNotFound) {
        public static Optional<Decision> deny(String policy, String reason) { return Optional.of(new Decision(false, policy, reason, false)); }
        public static Optional<Decision> denyAsNotFound(String policy, String reason) { return Optional.of(new Decision(false, policy, reason, true)); }
    }

    Optional<Decision> evaluate(Request request);
}
