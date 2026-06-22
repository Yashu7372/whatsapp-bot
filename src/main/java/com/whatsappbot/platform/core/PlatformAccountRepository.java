package com.whatsappbot.platform.core;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlatformAccountRepository extends JpaRepository<PlatformAccountEntity, UUID> {

    List<PlatformAccountEntity> findAllByTenantIdAndActive(UUID tenantId, boolean active);

    List<PlatformAccountEntity> findAllByTenantIdAndPlatformCodeAndActive(
            UUID tenantId, String platformCode, boolean active);
}
