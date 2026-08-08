package com.whatsappbot.forecast;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/forecast-intelligence")
@RequiredArgsConstructor
public class ForecastIntelligenceController {
    private final ForecastIntelligenceService service;

    @GetMapping
    public ResponseEntity<ForecastIntelligenceService.Dashboard> dashboard(@AuthenticationPrincipal Claims claims,
                                                                           @PathVariable UUID projectId) {
        return ResponseEntity.ok(service.dashboard(tenantId(claims), userId(claims), projectId));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ForecastIntelligenceService.Dashboard> refresh(@AuthenticationPrincipal Claims claims,
                                                                         @PathVariable UUID projectId) {
        return ResponseEntity.ok(service.refresh(tenantId(claims), userId(claims), projectId));
    }

    private static UUID tenantId(Claims claims) { return UUID.fromString((String) claims.get("tenantId")); }
    private static UUID userId(Claims claims) { return UUID.fromString(claims.getSubject()); }
}
