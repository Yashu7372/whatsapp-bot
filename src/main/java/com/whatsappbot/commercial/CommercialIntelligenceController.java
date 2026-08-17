package com.whatsappbot.commercial;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/commercial")
@RequiredArgsConstructor
public class CommercialIntelligenceController {

    private final CommercialIntelligenceService service;

    @GetMapping("/overview")
    public ResponseEntity<CommercialIntelligenceService.CommercialOverview> overview(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "true") boolean includeAi) {
        return ResponseEntity.ok(service.overview(tenantId(claims), userId(claims), projectId, includeAi));
    }

    private static UUID tenantId(Claims claims) {
        return UUID.fromString((String) claims.get("tenantId"));
    }

    private static UUID userId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }
}
