package com.whatsappbot.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantUserRepository extends JpaRepository<TenantUserEntity, UUID> {
    Optional<TenantUserEntity> findByEmailAndActiveTrue(String email);

    boolean existsByTenantIdAndEmailIgnoreCaseAndActiveTrue(UUID tenantId, String email);
}
