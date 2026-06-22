package com.whatsappbot.analytics;

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
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsIngestionService analyticsIngestionService;
    private final AnalyticsSnapshotRepository analyticsSnapshotRepository;
    private final TenantRepository tenantRepository;

    @PostMapping("/ingest")
    public ResponseEntity<AnalyticsSnapshotEntity> ingest(@AuthenticationPrincipal Claims claims,
                                                           @RequestBody IngestRequest request) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        AnalyticsSnapshotEntity snapshot = analyticsIngestionService.ingest(
                tenant, request.publishJobId(), request.platformCode(),
                request.views(), request.likes(), request.comments(),
                request.shares(), request.clicks(), request.leads());
        return ResponseEntity.ok(snapshot);
    }

    @GetMapping("/snapshots")
    public ResponseEntity<List<AnalyticsSnapshotEntity>> listSnapshots(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return ResponseEntity.ok(analyticsSnapshotRepository.findAllByTenantIdOrderByCapturedAtDesc(tenantId));
    }

    record IngestRequest(UUID publishJobId, String platformCode,
                         long views, long likes, long comments, long shares, long clicks, long leads) {}
}
