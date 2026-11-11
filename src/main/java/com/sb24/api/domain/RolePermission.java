package com.sb24.api.domain;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "role_permission")
@Getter @Setter @NoArgsConstructor
public class RolePermission {
    @Id private UUID id = UUID.randomUUID();
    private String roleCode;
    private String permissionCode;
}
