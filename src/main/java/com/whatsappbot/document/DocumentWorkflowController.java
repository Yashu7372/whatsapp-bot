package com.whatsappbot.document;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/document-workflows")
@RequiredArgsConstructor
public class DocumentWorkflowController {

    private final DocumentService documentService;

    @PostMapping
    public ResponseEntity<WorkflowResponse> create(
            @AuthenticationPrincipal Claims claims,
            @RequestBody DocumentService.CreateWorkflowRequest req) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return ResponseEntity.ok(toResponse(documentService.createWorkflow(tenantId, req)));
    }

    @GetMapping
    public ResponseEntity<List<WorkflowResponse>> list(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return ResponseEntity.ok(
                documentService.listWorkflows(tenantId).stream().map(this::toResponse).toList());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<WorkflowResponse> update(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestBody DocumentService.UpdateWorkflowRequest req) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return ResponseEntity.ok(toResponse(documentService.updateWorkflow(tenantId, id, req)));
    }

    private WorkflowResponse toResponse(DocumentControlWorkflowEntity w) {
        return new WorkflowResponse(w.getId(), w.getName(), w.getDocType(),
                w.getSteps(), w.isActive(), w.getCreatedAt(), w.getUpdatedAt());
    }

    public record WorkflowResponse(UUID id, String name, String docType, String steps,
                                    boolean active, LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
