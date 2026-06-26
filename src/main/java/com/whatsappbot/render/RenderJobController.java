package com.whatsappbot.render;

import com.whatsappbot.domain.tenant.TenantRepository;
import com.whatsappbot.features.FeatureAccessService;
import com.whatsappbot.features.FeatureCode;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/render-jobs")
@RequiredArgsConstructor
public class RenderJobController {

    private final RenderJobRepository renderJobRepository;
    private final TenantRepository tenantRepository;
    private final FeatureAccessService featureAccessService;

    @GetMapping
    public ResponseEntity<List<RenderJobResponse>> list(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        featureAccessService.assertAccess(tenantId, FeatureCode.VIDEO_TEMPLATE_ENGINE);
        return ResponseEntity.ok(
                renderJobRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId)
                        .stream().map(this::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RenderJobResponse> get(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        featureAccessService.assertAccess(tenantId, FeatureCode.VIDEO_TEMPLATE_ENGINE);
        RenderJobEntity job = renderJobRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Render job not found"));
        return ResponseEntity.ok(toResponse(job));
    }

    @PostMapping
    public ResponseEntity<RenderJobResponse> create(
            @AuthenticationPrincipal Claims claims,
            @RequestBody CreateRenderJobRequest req) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        featureAccessService.assertAccess(tenantId, FeatureCode.VIDEO_TEMPLATE_ENGINE);

        RenderJobEntity job = new RenderJobEntity();
        job.setTenant(tenantRepository.getReferenceById(tenantId));
        job.setContentItemId(req.contentItemId());
        job.setTemplateId(req.templateId());
        job.setRenderInstructions(req.renderInstructions() != null ? req.renderInstructions() : Map.of());
        return ResponseEntity.ok(toResponse(renderJobRepository.save(job)));
    }

    private RenderJobResponse toResponse(RenderJobEntity j) {
        return new RenderJobResponse(j.getId(), j.getContentItemId(), j.getTemplateId(),
                j.getStatus(), j.getOutputAssetId(), j.getErrorMessage(),
                j.getStartedAt(), j.getCompletedAt(), j.getCreatedAt());
    }

    public record CreateRenderJobRequest(UUID contentItemId, UUID templateId,
                                          Map<String, Object> renderInstructions) {}

    public record RenderJobResponse(UUID id, UUID contentItemId, UUID templateId,
                                     String status, UUID outputAssetId, String errorMessage,
                                     LocalDateTime startedAt, LocalDateTime completedAt,
                                     LocalDateTime createdAt) {}
}
