package com.sb24.api.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "subject_role")
@Getter @Setter @NoArgsConstructor
public class SubjectRole {
    @Id private UUID id = UUID.randomUUID();
    private UUID subjectId;
    private String roleCode;
    private UUID assignedBy;
    private Instant assignedAt = Instant.now();
}
