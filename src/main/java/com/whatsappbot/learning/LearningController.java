package com.whatsappbot.learning;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/learning")
@RequiredArgsConstructor
public class LearningController {

    private final LearningInsightService learningInsightService;

    @GetMapping("/insights")
    public ResponseEntity<List<LearningInsightEntity>> listInsights(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return ResponseEntity.ok(learningInsightService.listByTenant(tenantId));
    }

    @PostMapping("/generate")
    public ResponseEntity<List<LearningInsightEntity>> generate(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        List<LearningInsightEntity> insights = learningInsightService.generateForTenant(tenantId);
        return ResponseEntity.ok(insights);
    }
}
