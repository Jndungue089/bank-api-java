package com.sb24.api.repo;

import com.sb24.api.domain.SubjectRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubjectRoleRepository extends JpaRepository<SubjectRole, UUID> {
    List<SubjectRole> findBySubjectId(UUID subjectId);
    Optional<SubjectRole> findBySubjectIdAndRoleCode(UUID subjectId, String roleCode);

    @Query("select distinct rp.permissionCode from RolePermission rp where rp.roleCode in (select sr.roleCode from SubjectRole sr where sr.subjectId = :subjectId)")
    List<String> permissionsOf(@Param("subjectId") UUID subjectId);
}
