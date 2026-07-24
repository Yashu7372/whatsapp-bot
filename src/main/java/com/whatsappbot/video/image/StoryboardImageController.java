package com.whatsappbot.video.image;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class StoryboardImageController {

    private final StoryboardImageService imageService;

    @GetMapping("/storyboard-images/provider-status")
    public ResponseEntity<StoryboardImageService.ProviderStatus> providerStatus() {
        return ResponseEntity.ok(imageService.providerStatus());
    }

    @PostMapping("/video-scripts/{scriptId}/storyboard-images")
    public ResponseEntity<StoryboardImageJobResponse> generate(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID scriptId,
            @RequestBody GenerateStoryboardImageRequest request) {
        UUID tenantId = tenantId(claims);
        StoryboardImageJobEntity job = imageService.queue(
                tenantId,
                scriptId,
                request.characterProfileId(),
                request.shotIndex(),
                request.prompt(),
                request.qualityMode(),
                request.maximumShotCostUsd(),
                request.reelBudgetUsd()
        );
        return ResponseEntity.accepted().body(toResponse(job));
    }

    @GetMapping("/video-scripts/{scriptId}/storyboard-images")
    public ResponseEntity<List<StoryboardImageJobResponse>> list(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID scriptId) {
        return ResponseEntity.ok(imageService.list(tenantId(claims), scriptId)
                .stream()
                .map(this::toResponse)
                .toList());
    }

    @GetMapping("/storyboard-images/{jobId}")
    public ResponseEntity<StoryboardImageJobResponse> get(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID jobId) {
        return ResponseEntity.ok(toResponse(imageService.get(tenantId(claims), jobId)));
    }

    private StoryboardImageJobResponse toResponse(StoryboardImageJobEntity job) {
        return new StoryboardImageJobResponse(
                job.getId(),
                job.getVideoScript().getId(),
                job.getCharacterProfile() == null ? null : job.getCharacterProfile().getId(),
                job.getOutputAsset() == null ? null : job.getOutputAsset().getId(),
                job.getShotIndex(),
                job.getPrompt(),
                job.getQualityMode(),
                job.getProvider(),
                job.getStatus(),
                job.getEstimatedCostUsd(),
                job.getActualCostUsd(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getCompletedAt()
        );
    }

    private UUID tenantId(Claims claims) {
        return UUID.fromString((String) claims.get("tenantId"));
    }

    record GenerateStoryboardImageRequest(
            UUID characterProfileId,
            int shotIndex,
            String prompt,
            StoryboardQualityMode qualityMode,
            BigDecimal maximumShotCostUsd,
            BigDecimal reelBudgetUsd
    ) {
    }

    record StoryboardImageJobResponse(
            UUID id,
            UUID videoScriptId,
            UUID characterProfileId,
            UUID outputAssetId,
            int shotIndex,
            String prompt,
            StoryboardQualityMode qualityMode,
            String provider,
            StoryboardImageStatus status,
            BigDecimal estimatedCostUsd,
            BigDecimal actualCostUsd,
            String errorMessage,
            LocalDateTime createdAt,
            LocalDateTime completedAt
    ) {
    }
}
