package com.whatsappbot.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RolePermissionRepository extends JpaRepository<RolePermissionEntity, RolePermissionId> {

    boolean existsByRoleAndFeatureCodeAndActionAndAllowedTrue(String role, String featureCode, String action);

    List<RolePermissionEntity> findAllByRoleAndAllowedTrue(String role);

    Optional<RolePermissionEntity> findByRoleAndFeatureCodeAndAction(String role, String featureCode, String action);
}
