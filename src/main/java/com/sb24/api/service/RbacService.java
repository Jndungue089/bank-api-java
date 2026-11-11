package com.sb24.api.service;

import com.sb24.api.access.*;
import com.sb24.api.domain.AccessRole;
import com.sb24.api.domain.SubjectRole;
import com.sb24.api.repo.*;
import com.sb24.api.web.Dtos.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Role administration. Every change is permission-checked, realm-checked, reasoned and audited. */
@Service
public class RbacService {
    private final AccessRoleRepository roles;
    private final PermissionRepository permissions;
    private final RolePermissionRepository grants;
    private final SubjectRoleRepository subjectRoles;
    private final UserRepository users;
    private final AdminUserRepository admins;
    private final PartnerRepository partners;
    private final PermissionService permissionService;
    private final PolicyService policy;
    private final AuditService audit;

    public RbacService(AccessRoleRepository roles, PermissionRepository permissions, RolePermissionRepository grants,
                       SubjectRoleRepository subjectRoles, UserRepository users, AdminUserRepository admins, PartnerRepository partners,
                       PermissionService permissionService, PolicyService policy, AuditService audit) {
        this.roles = roles;
        this.permissions = permissions;
        this.grants = grants;
        this.subjectRoles = subjectRoles;
        this.users = users;
        this.admins = admins;
        this.partners = partners;
        this.permissionService = permissionService;
        this.policy = policy;
        this.audit = audit;
    }

    /** Internal grant used when an identity is created (registration, admin/partner creation, seeders). */
    @Transactional
    public void grant(UUID subjectId, String roleCode, UUID assignedBy) {
        if (!roles.existsById(roleCode)) throw new IllegalStateException("unknown role " + roleCode);
        if (subjectRoles.findBySubjectIdAndRoleCode(subjectId, roleCode).isPresent()) return;
        SubjectRole sr = new SubjectRole();
        sr.setSubjectId(subjectId);
        sr.setRoleCode(roleCode);
        sr.setAssignedBy(assignedBy);
        subjectRoles.save(sr);
        permissionService.invalidate(subjectId);
    }

    @Transactional(readOnly = true)
    public RbacCatalog catalog() {
        Map<String, List<String>> perms = new LinkedHashMap<>();
        roles.findAll().forEach(r -> perms.put(r.getCode(), grants.findByRoleCode(r.getCode()).stream().map(g -> g.getPermissionCode()).sorted().toList()));
        return new RbacCatalog(
                roles.findAll().stream().sorted(Comparator.comparing(AccessRole::getCode)).map(r -> new RoleView(r.getCode(), r.getRealm(), r.getDescription(), perms.get(r.getCode()))).toList(),
                permissions.findAll().stream().sorted(Comparator.comparing(p -> p.getCode())).map(p -> new PermissionView(p.getCode(), p.getDescription())).toList());
    }

    @Transactional(readOnly = true)
    public SubjectAccess access(UUID subjectId) {
        if (realmOf(subjectId) == null) throw ApiException.notFound("Utilizador não encontrado.");
        return new SubjectAccess(subjectId, realmOf(subjectId), new TreeSet<>(permissionService.rolesOf(subjectId)), new TreeSet<>(permissionService.permissionsOf(subjectId)));
    }

    @Transactional
    public SubjectAccess assign(UUID adminId, UUID subjectId, String roleCode, String reason) {
        AccessRole role = roles.findById(roleCode).orElseThrow(() -> ApiException.notFound("Papel não encontrado."));
        String realm = realmOf(subjectId);
        if (realm == null) throw ApiException.notFound("Utilizador não encontrado.");
        policy.require(Subject.admin(adminId), Perm.RBAC_MANAGE, Resource.of("SUBJECT", subjectId, null, Map.of("targetId", subjectId, "operation", "ASSIGN")));
        if (!role.getRealm().equalsIgnoreCase(realm)) {
            throw ApiException.unprocessable("REALM_MISMATCH", "O papel " + roleCode + " pertence ao realm " + role.getRealm() + ", mas o utilizador é " + realm + ".");
        }
        grant(subjectId, roleCode, adminId);
        audit.admin(adminId, "ADMIN_ASSIGN_ROLE", "SUCCESS", "SUBJECT", subjectId.toString(), "role=" + roleCode + " reason=" + reason);
        return access(subjectId);
    }

    @Transactional
    public SubjectAccess revoke(UUID adminId, UUID subjectId, String roleCode, String reason) {
        policy.require(Subject.admin(adminId), Perm.RBAC_MANAGE, Resource.of("SUBJECT", subjectId, null, Map.of("targetId", subjectId, "operation", "REVOKE")));
        SubjectRole sr = subjectRoles.findBySubjectIdAndRoleCode(subjectId, roleCode).orElseThrow(() -> ApiException.notFound("O utilizador não tem esse papel."));
        subjectRoles.delete(sr);
        permissionService.invalidate(subjectId);
        audit.admin(adminId, "ADMIN_REVOKE_ROLE", "SUCCESS", "SUBJECT", subjectId.toString(), "role=" + roleCode + " reason=" + reason);
        return access(subjectId);
    }

    private String realmOf(UUID id) {
        if (users.existsById(id)) return "CUSTOMER";
        if (admins.existsById(id)) return "ADMIN";
        if (partners.existsById(id)) return "PARTNER";
        return null;
    }
}
