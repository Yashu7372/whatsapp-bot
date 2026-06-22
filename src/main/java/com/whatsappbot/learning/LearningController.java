package com.whatsappbot.learning;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/learning")
@RequiredArgsConstructor
public class LearningController {

    private final LearningInsightService learningInsightService;

    @GetMapping("/insights")
    public ResponseEntity<List<LearningInsightEntity>> listInsights(@RequestParam UUID tenantId) {
        return ResponseEntity.ok(learningInsightService.listByTenant(tenantId));
    }
}
