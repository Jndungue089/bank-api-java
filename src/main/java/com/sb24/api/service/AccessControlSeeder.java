package com.sb24.api.service;

import com.sb24.api.access.Rbac;
import com.sb24.api.domain.AccessRole;
import com.sb24.api.domain.Permission;
import com.sb24.api.domain.RolePermission;
import com.sb24.api.repo.AccessRoleRepository;
import com.sb24.api.repo.PermissionRepository;
import com.sb24.api.repo.RolePermissionRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Seeder 3 (all profiles): permissions, roles and their default grants. Insert-if-missing: manual DB changes are never reverted. */
@Component
@Order(3)
public class AccessControlSeeder implements ApplicationRunner {
    private final PermissionRepository permissions;
    private final AccessRoleRepository roles;
    private final RolePermissionRepository grants;

    public AccessControlSeeder(PermissionRepository permissions, AccessRoleRepository roles, RolePermissionRepository grants) {
        this.permissions = permissions;
        this.roles = roles;
        this.grants = grants;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Rbac.PERMISSIONS.forEach((code, description) -> {
            if (!permissions.existsById(code)) {
                Permission p = new Permission();
                p.setCode(code);
                p.setDescription(description);
                permissions.save(p);
            }
        });
        Rbac.ROLES.forEach((code, def) -> {
            if (!roles.existsById(code)) {
                AccessRole r = new AccessRole();
                r.setCode(code);
                r.setRealm(def.realm());
                r.setDescription(def.description());
                roles.save(r);
            }
            for (String perm : def.permissions()) {
                if (!grants.existsByRoleCodeAndPermissionCode(code, perm)) {
                    RolePermission g = new RolePermission();
                    g.setRoleCode(code);
                    g.setPermissionCode(perm);
                    grants.save(g);
                }
            }
        });
    }
}
