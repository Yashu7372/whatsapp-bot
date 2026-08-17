package com.whatsappbot.document;

import com.whatsappbot.features.FeatureAccessService;
import com.whatsappbot.features.FeatureCode;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents/{documentId}/intelligence")
@RequiredArgsConstructor
public class DocumentIntelligenceController {

    private final DocumentIntelligenceService intelligenceService;
    private final DocumentAuthorizationService documentAuthorizationService;
    private final FeatureAccessService featureAccessService;

    @PostMapping("/analyze")
    public ResponseEntity<DocumentIntelligenceService.AnalysisView> analyze(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID documentId,
            @RequestParam(defaultValue = "false") boolean force) throws IOException {
        UUID tenantId = tenantId(claims);
        UUID userId = userId(claims);
        featureAccessService.assertAccess(tenantId, FeatureCode.DOCUMENT_CONTROL);
        documentAuthorizationService.requireView(tenantId, userId, documentId);
        return ResponseEntity.ok(intelligenceService.analyze(tenantId, userId, documentId, force));
    }

    @GetMapping
    public ResponseEntity<DocumentIntelligenceService.AnalysisView> latest(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID documentId) {
        UUID tenantId = tenantId(claims);
        UUID userId = userId(claims);
        featureAccessService.assertAccess(tenantId, FeatureCode.DOCUMENT_CONTROL);
        documentAuthorizationService.requireView(tenantId, userId, documentId);
        return ResponseEntity.ok(intelligenceService.latest(tenantId, documentId));
    }

    private static UUID tenantId(Claims claims) {
        return UUID.fromString((String) claims.get("tenantId"));
    }

    private static UUID userId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }
}
