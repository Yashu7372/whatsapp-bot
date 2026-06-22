package com.whatsappbot.content;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
            @RequestParam UUID tenantId,
            @RequestParam(required = false) UUID campaignId) {
        return ResponseEntity.ok(contentGenerationService.listIdeas(tenantId, campaignId));
    }

    @PostMapping("/generate")
    public ResponseEntity<ContentIdeaEntity> generateIdea(@RequestBody GenerateRequest request) {
        TenantEntity tenant = tenantRepository.findById(request.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + request.tenantId()));
        ContentIdeaEntity idea = contentGenerationService.generateIdea(
                tenant, request.campaignId(), request.platformCode(), request.contentType(), request.topic());
        return ResponseEntity.ok(idea);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ContentIdeaEntity> updateStatus(@PathVariable UUID id,
                                                           @RequestBody StatusRequest request) {
        ContentStatus status = ContentStatus.valueOf(request.status().toUpperCase());
        ContentIdeaEntity updated = contentGenerationService.updateStatus(id, status);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/{id}/variants")
    public ResponseEntity<List<ContentVariantEntity>> getVariants(@PathVariable UUID id) {
        return ResponseEntity.ok(contentVariantRepository.findAllByContentIdeaId(id));
    }

    record GenerateRequest(UUID tenantId, UUID campaignId, String platformCode, String contentType, String topic) {}

    record StatusRequest(String status) {}
}
