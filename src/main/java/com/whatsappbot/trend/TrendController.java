package com.whatsappbot.trend;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
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
    public ResponseEntity<List<TrendSignalResponse>> listSignals(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = tenantId(claims);
        return ResponseEntity.ok(trendSignalRepository.findAllByTenantIdOrderByFinalScoreDesc(tenantId)
                .stream()
                .map(this::toResponse)
                .toList());
    }

    @PostMapping("/import")
    public ResponseEntity<TrendSignalResponse> importSignal(@AuthenticationPrincipal Claims claims,
                                                             @RequestBody ImportRequest request) {
        UUID tenantId = tenantId(claims);
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        TrendSignalEntity signal = trendImportService.importSignal(
                tenant, request.keyword(), request.hashtag(), request.topic(),
                request.country(), request.industry(), request.platformCode(), request.rawScore());
        return ResponseEntity.ok(toResponse(signal));
    }

    @PostMapping("/discover")
    public ResponseEntity<List<TrendSignalResponse>> discover(@AuthenticationPrincipal Claims claims,
                                                               @RequestBody DiscoverRequest request) {
        UUID tenantId = tenantId(claims);
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        int count = request.count() > 0 ? Math.min(request.count(), 10) : 5;
        List<TrendSignalResponse> discovered = trendDiscoveryService.discover(
                        tenant,
                        request.industry() != null ? request.industry() : "General",
                        request.country() != null ? request.country() : "Global",
                        request.platformCode() != null ? request.platformCode() : "INSTAGRAM",
                        count
                ).stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(discovered);
    }

    @GetMapping(value = "/{id}/recommend", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> recommend(@AuthenticationPrincipal Claims claims,
                                             @PathVariable UUID id) {
        UUID tenantId = tenantId(claims);
        return trendSignalRepository.findById(id)
                .filter(trend -> trend.getTenant().getId().equals(tenantId))
                .map(trend -> ResponseEntity.ok(
                        trendDiscoveryService.recommend(
                                trend.getKeyword() != null ? trend.getKeyword() : trend.getTopic(),
                                trend.getTopic(),
                                trend.getPlatformCode()
                        )))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Claims claims, @PathVariable UUID id) {
        UUID tenantId = tenantId(claims);
        trendSignalRepository.findById(id).ifPresent(trend -> {
            if (trend.getTenant().getId().equals(tenantId)) {
                trendSignalRepository.delete(trend);
            }
        });
        return ResponseEntity.noContent().build();
    }

    private UUID tenantId(Claims claims) {
        return UUID.fromString((String) claims.get("tenantId"));
    }

    private TrendSignalResponse toResponse(TrendSignalEntity trend) {
        return new TrendSignalResponse(
                trend.getId(),
                trend.getSourceId(),
                trend.getKeyword(),
                trend.getHashtag(),
                trend.getTopic(),
                trend.getCountry(),
                trend.getIndustry(),
                trend.getPlatformCode(),
                trend.getRawScore(),
                trend.getFinalScore(),
                trend.getFreshnessScore(),
                trend.getGrowthScore(),
                trend.getRelevanceScore(),
                trend.getEngagementScore(),
                trend.getBrandSafetyScore(),
                trend.getCapturedAt(),
                trend.getCreatedAt()
        );
    }

    record ImportRequest(String keyword, String hashtag, String topic,
                         String country, String industry, String platformCode, double rawScore) {}

    record DiscoverRequest(String industry, String country, String platformCode, int count) {}

    public record TrendSignalResponse(
            UUID id,
            UUID sourceId,
            String keyword,
            String hashtag,
            String topic,
            String country,
            String industry,
            String platformCode,
            double rawScore,
            double finalScore,
            double freshnessScore,
            double growthScore,
            double relevanceScore,
            double engagementScore,
            double brandSafetyScore,
            LocalDateTime capturedAt,
            LocalDateTime createdAt
    ) {}
}
