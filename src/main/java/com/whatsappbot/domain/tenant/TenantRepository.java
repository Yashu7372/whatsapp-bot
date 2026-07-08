package com.whatsappbot.domain.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<TenantEntity, UUID> {
    Optional<TenantEntity> findByPhoneNumberIdAndActiveTrue(String phoneNumberId);
    Optional<TenantEntity> findByTenantCode(String tenantCode);
    Optional<TenantEntity> findByTenantCodeAndActiveTrue(String tenantCode);
}
