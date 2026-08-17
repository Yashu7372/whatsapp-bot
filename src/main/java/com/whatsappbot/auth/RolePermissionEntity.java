package com.whatsappbot.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "role_permissions")
@IdClass(RolePermissionId.class)
public class RolePermissionEntity {

    @Id
    @Column(name = "role", length = 50)
    private String role;

    @Id
    @Column(name = "feature_code", length = 100)
    private String featureCode;

    @Id
    @Column(name = "action", length = 50)
    private String action;

    @Column(name = "allowed", nullable = false)
    private boolean allowed;
}
