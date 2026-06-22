package com.whatsappbot.content;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ContentIdeaRepository extends JpaRepository<ContentIdeaEntity, UUID> {
    List<ContentIdeaEntity> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);
    List<ContentIdeaEntity> findAllByTenantIdAndCampaignIdOrderByCreatedAtDesc(UUID tenantId, UUID campaignId);
}
