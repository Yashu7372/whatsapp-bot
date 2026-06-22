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

    record ImportRequest(String keyword, String hashtag, String topic,
                         String country, String industry, String platformCode, double rawScore) {}
}
