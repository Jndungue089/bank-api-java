package com.sb24.api.repo;

import com.sb24.api.domain.RolePermission;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolePermissionRepository extends JpaRepository<RolePermission, UUID> {
    List<RolePermission> findByRoleCode(String roleCode);
    boolean existsByRoleCodeAndPermissionCode(String roleCode, String permissionCode);
}
