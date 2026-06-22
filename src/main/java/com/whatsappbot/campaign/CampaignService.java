package com.whatsappbot.campaign;

import com.whatsappbot.domain.tenant.TenantEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignService {

    private final CampaignRepository campaignRepository;

    @Transactional
    public CampaignEntity createCampaign(TenantEntity tenant, String name, String goal, String brief) {
        CampaignGoal campaignGoal = CampaignGoal.valueOf(goal.toUpperCase());
        CampaignEntity campaign = CampaignEntity.create(tenant, name, campaignGoal, brief);
        CampaignEntity saved = campaignRepository.save(campaign);
        log.info("Created campaign id={} name={} tenant={}", saved.getId(), name, tenant.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<CampaignEntity> listCampaigns(UUID tenantId) {
        return campaignRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Transactional
    public CampaignEntity updateStatus(UUID campaignId, CampaignStatus newStatus) {
        CampaignEntity campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + campaignId));
        campaign.setStatus(newStatus);
        return campaignRepository.save(campaign);
    }
}
