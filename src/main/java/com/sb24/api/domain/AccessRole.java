package com.sb24.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "access_role")
@Getter @Setter @NoArgsConstructor
public class AccessRole {
    @Id private String code;
    private String realm;
    private String description;
}
