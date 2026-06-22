package com.whatsappbot.lead;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LeadSignalRepository extends JpaRepository<LeadSignalEntity, UUID> {
    List<LeadSignalEntity> findAllByTenantIdOrderByScoreDesc(UUID tenantId);
    List<LeadSignalEntity> findAllByTenantIdOrderByCapturedAtDesc(UUID tenantId);
}
