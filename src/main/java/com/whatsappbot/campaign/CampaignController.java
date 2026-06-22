package com.whatsappbot.campaign;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    public ResponseEntity<List<CampaignEntity>> listCampaigns(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return ResponseEntity.ok(campaignService.listCampaigns(tenantId));
    }

    @PostMapping
    public ResponseEntity<CampaignEntity> createCampaign(@AuthenticationPrincipal Claims claims,
                                                          @RequestBody CreateCampaignRequest request) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        return ResponseEntity.ok(campaignService.createCampaign(tenant, request.name(), request.goal(), request.brief()));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<CampaignEntity> updateStatus(@PathVariable UUID id,
                                                        @RequestBody StatusRequest request) {
        CampaignStatus newStatus = CampaignStatus.valueOf(request.status().toUpperCase());
        return ResponseEntity.ok(campaignService.updateStatus(id, newStatus));
    }

    record CreateCampaignRequest(String name, String goal, String brief) {}

    record StatusRequest(String status) {}
}
