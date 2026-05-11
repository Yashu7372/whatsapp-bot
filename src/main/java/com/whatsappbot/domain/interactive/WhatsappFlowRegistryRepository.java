package com.whatsappbot.domain.interactive;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WhatsappFlowRegistryRepository extends JpaRepository<WhatsappFlowRegistryEntity, UUID> {
    Optional<WhatsappFlowRegistryEntity> findByTenantIdAndFlowKeyAndActiveTrue(UUID tenantId, String flowKey);
}
