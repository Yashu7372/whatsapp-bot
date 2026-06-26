package com.whatsappbot.templates;

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
@RequestMapping("/api/v1/templates")
@RequiredArgsConstructor
public class VideoTemplateController {

    private final VideoTemplateRepository templateRepository;
    private final TenantRepository tenantRepository;
    private final FeatureAccessService featureAccessService;

    @GetMapping
    public ResponseEntity<List<TemplateResponse>> list(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        featureAccessService.assertAccess(tenantId, FeatureCode.VIDEO_TEMPLATE_ENGINE);
        return ResponseEntity.ok(
                templateRepository.findAvailableForTenant(tenantId)
                        .stream().map(this::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TemplateResponse> get(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        featureAccessService.assertAccess(tenantId, FeatureCode.VIDEO_TEMPLATE_ENGINE);
        VideoTemplateEntity t = templateRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found"));
        return ResponseEntity.ok(toResponse(t));
    }

    @PostMapping
    public ResponseEntity<TemplateResponse> create(
            @AuthenticationPrincipal Claims claims,
            @RequestBody CreateTemplateRequest req) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        featureAccessService.assertAccess(tenantId, FeatureCode.VIDEO_TEMPLATE_ENGINE);

        VideoTemplateEntity t = new VideoTemplateEntity();
        t.setTenant(tenantRepository.getReferenceById(tenantId));
        t.setScope("TENANT");
        t.setName(req.name());
        t.setCategory(req.category());
        t.setFormat(req.format());
        t.setConfig(req.config() != null ? req.config() : Map.of());
        return ResponseEntity.ok(toResponse(templateRepository.save(t)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<TemplateResponse> update(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestBody UpdateTemplateRequest req) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        featureAccessService.assertAccess(tenantId, FeatureCode.VIDEO_TEMPLATE_ENGINE);

        VideoTemplateEntity t = templateRepository.findById(id)
                .filter(e -> e.getTenant() != null && e.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found or not owned by tenant"));

        if (req.name() != null) t.setName(req.name());
        if (req.category() != null) t.setCategory(req.category());
        if (req.config() != null) t.setConfig(req.config());
        if (req.active() != null) t.setActive(req.active());
        return ResponseEntity.ok(toResponse(templateRepository.save(t)));
    }

    private TemplateResponse toResponse(VideoTemplateEntity t) {
        return new TemplateResponse(t.getId(), t.getScope(), t.getName(), t.getCategory(),
                t.getFormat(), t.getConfig(), t.isActive(), t.getCreatedAt());
    }

    public record CreateTemplateRequest(String name, String category, String format, Map<String, Object> config) {}
    public record UpdateTemplateRequest(String name, String category, Map<String, Object> config, Boolean active) {}
    public record TemplateResponse(UUID id, String scope, String name, String category,
                                    String format, Map<String, Object> config, boolean active,
                                    LocalDateTime createdAt) {}
}
