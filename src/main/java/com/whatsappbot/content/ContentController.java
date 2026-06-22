package com.whatsappbot.content;

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
@RequestMapping("/api/v1/content-ideas")
@RequiredArgsConstructor
public class ContentController {

    private final ContentGenerationService contentGenerationService;
    private final ContentVariantRepository contentVariantRepository;
    private final TenantRepository tenantRepository;

    @GetMapping
    public ResponseEntity<List<ContentIdeaEntity>> listIdeas(
            @AuthenticationPrincipal Claims claims,
            @RequestParam(required = false) UUID campaignId) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return ResponseEntity.ok(contentGenerationService.listIdeas(tenantId, campaignId));
    }

    @PostMapping("/generate")
    public ResponseEntity<ContentIdeaEntity> generateIdea(@AuthenticationPrincipal Claims claims,
                                                           @RequestBody GenerateRequest request) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        ContentIdeaEntity idea = contentGenerationService.generateIdea(
                tenant, request.campaignId(), request.platformCode(), request.contentType(), request.topic());
        return ResponseEntity.ok(idea);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ContentIdeaEntity> updateStatus(@PathVariable UUID id,
                                                           @RequestBody StatusRequest request) {
        ContentStatus status = ContentStatus.valueOf(request.status().toUpperCase());
        return ResponseEntity.ok(contentGenerationService.updateStatus(id, status));
    }

    @GetMapping("/{id}/variants")
    public ResponseEntity<List<ContentVariantEntity>> getVariants(@PathVariable UUID id) {
        return ResponseEntity.ok(contentVariantRepository.findAllByContentIdeaId(id));
    }

    record GenerateRequest(UUID campaignId, String platformCode, String contentType, String topic) {}

    record StatusRequest(String status) {}
}
