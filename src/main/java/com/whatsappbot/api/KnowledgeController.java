package com.whatsappbot.api;

import com.whatsappbot.application.knowledge.KnowledgeIngestRequest;
import com.whatsappbot.application.knowledge.KnowledgeSearchResult;
import com.whatsappbot.application.knowledge.KnowledgeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/tenants/{tenantCode}/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @PostMapping
    public ResponseEntity<Map<String, UUID>> ingest(
            @PathVariable String tenantCode,
            @Valid @RequestBody KnowledgeIngestRequest request
    ) {
        UUID documentId = knowledgeService.ingest(tenantCode, request);
        return ResponseEntity.ok(Map.of("documentId", documentId));
    }

    @GetMapping("/search")
    public ResponseEntity<List<KnowledgeSearchResult>> search(
            @PathVariable String tenantCode,
            @RequestParam @NotBlank String q,
            @RequestParam(defaultValue = "5") int limit
    ) {
        return ResponseEntity.ok(knowledgeService.search(tenantCode, q, limit));
    }
}
