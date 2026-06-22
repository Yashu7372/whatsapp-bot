package com.whatsappbot.campaign;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CampaignRepository extends JpaRepository<CampaignEntity, UUID> {
    List<CampaignEntity> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
