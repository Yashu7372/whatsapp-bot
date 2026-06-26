package com.whatsappbot.publisher;

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
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/publishing-jobs")
@RequiredArgsConstructor
public class PublishingJobController {

    private final PublishingJobRepository publishingJobRepository;
    private final TenantRepository tenantRepository;
    private final FeatureAccessService featureAccessService;

    @GetMapping
    public ResponseEntity<List<PublishingJobResponse>> list(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        featureAccessService.assertAccess(tenantId, FeatureCode.SCHEDULED_PUBLISHING);
        return ResponseEntity.ok(
                publishingJobRepository.findAllByTenantIdOrderByScheduledAtDesc(tenantId)
                        .stream().map(this::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PublishingJobResponse> get(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        featureAccessService.assertAccess(tenantId, FeatureCode.SCHEDULED_PUBLISHING);
        PublishingJobEntity job = publishingJobRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Publishing job not found"));
        return ResponseEntity.ok(toResponse(job));
    }

    @PostMapping
    public ResponseEntity<PublishingJobResponse> create(
            @AuthenticationPrincipal Claims claims,
            @RequestBody CreatePublishingJobRequest req) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        featureAccessService.assertAccess(tenantId, FeatureCode.SCHEDULED_PUBLISHING);

        PublishingJobEntity job = new PublishingJobEntity();
        job.setTenant(tenantRepository.getReferenceById(tenantId));
        job.setContentItemId(req.contentItemId());
        job.setSocialAccountId(req.socialAccountId());
        job.setPlatform(req.platform());
        job.setCaption(req.caption());
        job.setHashtags(req.hashtags() != null ? req.hashtags() : new String[0]);
        job.setScheduledAt(req.scheduledAt());
        return ResponseEntity.ok(toResponse(publishingJobRepository.save(job)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        featureAccessService.assertAccess(tenantId, FeatureCode.SCHEDULED_PUBLISHING);
        PublishingJobEntity job = publishingJobRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Publishing job not found"));
        job.setStatus("CANCELLED");
        publishingJobRepository.save(job);
        return ResponseEntity.noContent().build();
    }

    private PublishingJobResponse toResponse(PublishingJobEntity j) {
        return new PublishingJobResponse(j.getId(), j.getContentItemId(), j.getSocialAccountId(),
                j.getPlatform(), j.getStatus(), j.getScheduledAt(),
                j.getExternalPostUrl(), j.getCreatedAt());
    }

    public record CreatePublishingJobRequest(UUID contentItemId, UUID socialAccountId,
                                              String platform, String caption, String[] hashtags,
                                              LocalDateTime scheduledAt) {}

    public record PublishingJobResponse(UUID id, UUID contentItemId, UUID socialAccountId,
                                         String platform, String status, LocalDateTime scheduledAt,
                                         String externalPostUrl, LocalDateTime createdAt) {}
}
