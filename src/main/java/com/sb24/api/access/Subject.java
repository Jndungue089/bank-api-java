package com.sb24.api.access;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** The authenticated principal: an id plus the realm (customer | admin | partner) that issued it. Roles/permissions are looked up, not trusted from the token. */
public record Subject(UUID id, String realm) {
    public static Subject customer(UUID id) { return new Subject(id, "customer"); }
    public static Subject admin(UUID id) { return new Subject(id, "admin"); }
    public static Subject partner(UUID id) { return new Subject(id, "partner"); }

    public static Subject of(Jwt jwt) { return new Subject(UUID.fromString(jwt.getSubject()), String.valueOf(jwt.getClaimAsString("realm"))); }
}
