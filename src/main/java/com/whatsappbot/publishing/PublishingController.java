package com.whatsappbot.publishing;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/publish-jobs")
@RequiredArgsConstructor
public class PublishingController {

    private final PublishingService publishingService;
    private final TenantRepository tenantRepository;

    @GetMapping
    public ResponseEntity<List<PublishJobEntity>> listJobs(@RequestParam UUID tenantId) {
        return ResponseEntity.ok(publishingService.listJobs(tenantId));
    }

    @PostMapping
    public ResponseEntity<PublishJobEntity> scheduleJob(@RequestBody ScheduleRequest request) {
        TenantEntity tenant = tenantRepository.findById(request.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + request.tenantId()));
        LocalDateTime scheduledAt = LocalDateTime.parse(request.scheduledAt());
        PublishJobEntity job = publishingService.scheduleJob(
                tenant, request.contentIdeaId(), request.platformAccountId(),
                request.platformCode(), scheduledAt, request.contentSnapshot());
        return ResponseEntity.ok(job);
    }

    record ScheduleRequest(UUID tenantId, UUID contentIdeaId, UUID platformAccountId,
                           String platformCode, String scheduledAt, String contentSnapshot) {}
}
