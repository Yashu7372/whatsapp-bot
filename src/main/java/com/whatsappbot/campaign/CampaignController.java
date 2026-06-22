package com.whatsappbot.campaign;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/campaigns")
@RequiredArgsConstructor
public class CampaignController {

    private final CampaignService campaignService;
    private final TenantRepository tenantRepository;

    @GetMapping
    public ResponseEntity<List<CampaignEntity>> listCampaigns(@RequestParam UUID tenantId) {
        return ResponseEntity.ok(campaignService.listCampaigns(tenantId));
    }

    @PostMapping
    public ResponseEntity<CampaignEntity> createCampaign(@RequestBody CreateCampaignRequest request) {
        TenantEntity tenant = tenantRepository.findById(request.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + request.tenantId()));
        CampaignEntity campaign = campaignService.createCampaign(tenant, request.name(), request.goal(), request.brief());
        return ResponseEntity.ok(campaign);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<CampaignEntity> updateStatus(@PathVariable UUID id, @RequestBody StatusRequest request) {
        CampaignStatus newStatus = CampaignStatus.valueOf(request.status().toUpperCase());
        CampaignEntity updated = campaignService.updateStatus(id, newStatus);
        return ResponseEntity.ok(updated);
    }

    record CreateCampaignRequest(UUID tenantId, String name, String goal, String brief) {}

    record StatusRequest(String status) {}
}
