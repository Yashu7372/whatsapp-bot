package com.whatsappbot.auth;

import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@EqualsAndHashCode
@NoArgsConstructor
public class RolePermissionId implements Serializable {

    private String role;
    private String featureCode;
    private String action;

    public RolePermissionId(String role, String featureCode, String action) {
        this.role = role;
        this.featureCode = featureCode;
        this.action = action;
    }
}
