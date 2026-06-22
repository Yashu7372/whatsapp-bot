package com.whatsappbot.analytics;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<AnalyticsSnapshotEntity> ingest(@RequestBody IngestRequest request) {
        TenantEntity tenant = tenantRepository.findById(request.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + request.tenantId()));
        AnalyticsSnapshotEntity snapshot = analyticsIngestionService.ingest(
                tenant, request.publishJobId(), request.platformCode(),
                request.views(), request.likes(), request.comments(),
                request.shares(), request.clicks(), request.leads());
        return ResponseEntity.ok(snapshot);
    }

    @GetMapping("/snapshots")
    public ResponseEntity<List<AnalyticsSnapshotEntity>> listSnapshots(@RequestParam UUID tenantId) {
        return ResponseEntity.ok(analyticsSnapshotRepository.findAllByTenantIdOrderByCapturedAtDesc(tenantId));
    }

    record IngestRequest(UUID tenantId, UUID publishJobId, String platformCode,
                         long views, long likes, long comments, long shares, long clicks, long leads) {}
}
