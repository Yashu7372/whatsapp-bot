package com.whatsappbot.video.engine.job;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import com.whatsappbot.video.engine.GenerationMode;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/video-generations")
@RequiredArgsConstructor
public class GenerationJobController {

    private final GenerationJobService generationJobService;
    private final TenantRepository tenantRepository;

    @PostMapping
    public ResponseEntity<GenerationJobService.GenerationJobResponse> create(
            @AuthenticationPrincipal Claims claims,
            @RequestBody CreateRequest request
    ) {
        UUID tenantId = tenantId(claims);
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));

        int duration = request.targetDurationSeconds() <= 0 ? 30 : request.targetDurationSeconds();
        return ResponseEntity.ok(generationJobService.create(
                tenant,
                new GenerationJobService.CreateGenerationJob(
                        request.topic(),
                        request.mode(),
                        request.platform(),
                        duration,
                        request.options()
                )
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<GenerationJobService.GenerationJobResponse> get(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(generationJobService.get(tenantId(claims), id));
    }

    @PostMapping("/{id}/advance")
    public ResponseEntity<GenerationJobService.GenerationJobResponse> advance(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(generationJobService.advance(tenantId(claims), id));
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<GenerationJobService.GenerationJobResponse> run(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id
    ) {
        UUID tenantId = tenantId(claims);
        GenerationJobService.GenerationJobResponse current = generationJobService.get(tenantId, id);
        for (int i = 0; i < 8 && current.status() == GenerationJobStatus.READY; i++) {
            current = generationJobService.advance(tenantId, id);
        }
        return ResponseEntity.ok(current);
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<GenerationJobService.GenerationJobResponse> retry(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(generationJobService.retry(tenantId(claims), id));
    }

    private UUID tenantId(Claims claims) {
        return UUID.fromString((String) claims.get("tenantId"));
    }

    public record CreateRequest(
            String topic,
            GenerationMode mode,
            String platform,
            int targetDurationSeconds,
            Map<String, String> options
    ) {}
}
