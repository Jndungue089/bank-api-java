package com.sb24.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "permission")
@Getter @Setter @NoArgsConstructor
public class Permission {
    @Id private String code;
    private String description;
}
