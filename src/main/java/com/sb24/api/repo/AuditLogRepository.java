package com.sb24.api.repo;

import com.sb24.api.domain.AuditLog;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    @Query("select a from AuditLog a where (:actorId is null or a.actorId = :actorId) and (:action is null or a.action = :action) order by a.createdAt desc")
    Page<AuditLog> search(@Param("actorId") UUID actorId, @Param("action") String action, Pageable pageable);
}
