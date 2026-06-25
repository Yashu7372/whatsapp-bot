package com.whatsappbot.trend;

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
@RequestMapping("/api/v1/trends")
@RequiredArgsConstructor
public class TrendController {

    private final TrendSignalRepository trendSignalRepository;
    private final TrendImportService trendImportService;
    private final TrendDiscoveryService trendDiscoveryService;
    private final TenantRepository tenantRepository;

    @GetMapping
    public ResponseEntity<List<TrendSignalEntity>> listSignals(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return ResponseEntity.ok(trendSignalRepository.findAllByTenantIdOrderByFinalScoreDesc(tenantId));
    }

    @PostMapping("/import")
    public ResponseEntity<TrendSignalEntity> importSignal(@AuthenticationPrincipal Claims claims,
                                                           @RequestBody ImportRequest request) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        TrendSignalEntity signal = trendImportService.importSignal(
                tenant, request.keyword(), request.hashtag(), request.topic(),
                request.country(), request.industry(), request.platformCode(), request.rawScore());
        return ResponseEntity.ok(signal);
    }

    @PostMapping("/discover")
    public ResponseEntity<List<TrendSignalEntity>> discover(@AuthenticationPrincipal Claims claims,
                                                             @RequestBody DiscoverRequest request) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        int count = request.count() > 0 ? Math.min(request.count(), 10) : 5;
        List<TrendSignalEntity> discovered = trendDiscoveryService.discover(
                tenant,
                request.industry() != null ? request.industry() : "General",
                request.country() != null ? request.country() : "Global",
                request.platformCode() != null ? request.platformCode() : "INSTAGRAM",
                count
        );
        return ResponseEntity.ok(discovered);
    }

    @GetMapping("/{id}/recommend")
    public ResponseEntity<String> recommend(@AuthenticationPrincipal Claims claims,
                                             @PathVariable UUID id) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return trendSignalRepository.findById(id)
                .filter(t -> t.getTenant().getId().equals(tenantId))
                .map(t -> ResponseEntity.ok(
                        trendDiscoveryService.recommend(
                                t.getKeyword() != null ? t.getKeyword() : t.getTopic(),
                                t.getTopic(),
                                t.getPlatformCode()
                        )))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Claims claims, @PathVariable UUID id) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        trendSignalRepository.findById(id).ifPresent(t -> {
            if (t.getTenant().getId().equals(tenantId)) {
                trendSignalRepository.delete(t);
            }
        });
        return ResponseEntity.noContent().build();
    }

    record ImportRequest(String keyword, String hashtag, String topic,
                         String country, String industry, String platformCode, double rawScore) {}

    record DiscoverRequest(String industry, String country, String platformCode, int count) {}
}

