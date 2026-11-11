package com.sb24.api.access;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sb24.api.repo.SubjectRoleRepository;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Resolves what a subject may do FROM THE DATABASE (roles -> permissions) on every request, with a 30 s cache.
 * Nothing is read from the token, so revoking a role takes effect at once on this instance and within 30 s on others.
 */
@Service
public class PermissionService {
    private final SubjectRoleRepository subjectRoles;
    private final Cache<UUID, Set<String>> cache = Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(Duration.ofSeconds(30)).build();
    private final Cache<UUID, Set<String>> roleCache = Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(Duration.ofSeconds(30)).build();

    public PermissionService(SubjectRoleRepository subjectRoles) { this.subjectRoles = subjectRoles; }

    public Set<String> permissionsOf(UUID subjectId) {
        return cache.get(subjectId, id -> new HashSet<>(subjectRoles.permissionsOf(id)));
    }

    public Set<String> rolesOf(UUID subjectId) {
        return roleCache.get(subjectId, id -> {
            Set<String> r = new HashSet<>();
            subjectRoles.findBySubjectId(id).forEach(sr -> r.add(sr.getRoleCode()));
            return r;
        });
    }

    public boolean has(UUID subjectId, String permission) { return permissionsOf(subjectId).contains(permission); }

    public void invalidate(UUID subjectId) {
        cache.invalidate(subjectId);
        roleCache.invalidate(subjectId);
    }
}
