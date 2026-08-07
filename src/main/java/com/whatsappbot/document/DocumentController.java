package com.whatsappbot.document;

import com.whatsappbot.features.FeatureAccessService;
import com.whatsappbot.features.FeatureCode;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentAuditService documentAuditService;
    private final FeatureAccessService featureAccessService;

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<DocumentResponse> create(
            @AuthenticationPrincipal Claims claims,
            @RequestPart("title") String title,
            @RequestPart(value = "docType", required = false) String docType,
            @RequestPart(value = "description", required = false) String description,
            @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        UUID userId   = UUID.fromString(claims.getSubject());

        featureAccessService.assertAccess(tenantId, FeatureCode.DOCUMENT_CONTROL);

        var req = new DocumentService.CreateDocumentRequest(title, docType, description, null);
        DocumentEntity doc = documentService.createDocument(tenantId, userId, req, file);
        documentAuditService.record(tenantId, doc.getId(), userId,
                DocumentAuditService.DOCUMENT_CREATED,
                Map.of("title", doc.getTitle(), "docType", doc.getDocType()));
        return ResponseEntity.ok(toResponse(doc));
    }

    // ── Zero-knowledge encrypted upload ───────────────────────────────────

    @PostMapping(value = "/encrypted", consumes = "multipart/form-data")
    public ResponseEntity<DocumentResponse> createEncrypted(
            @AuthenticationPrincipal Claims claims,
            @RequestPart("metadata") String metadataJson,
            @RequestPart(value = "encryptedFile", required = false) MultipartFile encryptedFile)
            throws IOException {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        UUID userId   = UUID.fromString(claims.getSubject());

        featureAccessService.assertAccess(tenantId, FeatureCode.DOCUMENT_CONTROL);
        featureAccessService.assertFeatureEnabled(tenantId, FeatureCode.ZERO_KNOWLEDGE_STORAGE);

        DocumentEntity doc = documentService.createEncryptedDocument(tenantId, userId, metadataJson, encryptedFile);
        documentAuditService.record(tenantId, doc.getId(), userId,
                DocumentAuditService.DOCUMENT_CREATED,
                Map.of("title", doc.getTitle(), "mode", "ZERO_KNOWLEDGE"));
        return ResponseEntity.ok(toResponse(doc));
    }

    // ── Audit trail ───────────────────────────────────────────────────────

    @GetMapping("/{id}/audit")
    public ResponseEntity<List<AuditEventResponse>> auditTrail(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        featureAccessService.assertAccess(tenantId, FeatureCode.DOCUMENT_CONTROL);
        return ResponseEntity.ok(
                documentAuditService.getAuditTrail(tenantId, id)
                        .stream().map(e -> new AuditEventResponse(
                                e.getId(), e.getDocumentId(), e.getEventType(),
                                e.getActorUser() != null ? e.getActorUser().getEmail() : null,
                                e.getEventPayload(), e.getCreatedAt()))
                        .toList());
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<DocumentResponse> createJson(
            @AuthenticationPrincipal Claims claims,
            @RequestBody DocumentService.CreateDocumentRequest req) throws IOException {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        UUID userId   = UUID.fromString(claims.getSubject());
        DocumentEntity doc = documentService.createDocument(tenantId, userId, req, null);
        return ResponseEntity.ok(toResponse(doc));
    }

    @GetMapping
    public ResponseEntity<List<DocumentResponse>> list(
            @AuthenticationPrincipal Claims claims,
            @RequestParam(required = false) String docType) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return ResponseEntity.ok(
                documentService.listDocuments(tenantId, docType)
                        .stream().map(this::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> get(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return ResponseEntity.ok(toResponse(documentService.getDocument(tenantId, id)));
    }

    @PutMapping(value = "/{id}", consumes = "multipart/form-data")
    public ResponseEntity<DocumentResponse> update(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestPart(value = "title", required = false) String title,
            @RequestPart(value = "description", required = false) String description,
            @RequestPart(value = "changeNotes", required = false) String changeNotes,
            @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        UUID userId   = UUID.fromString(claims.getSubject());
        var req = new DocumentService.UpdateDocumentRequest(title, description, null, changeNotes);
        return ResponseEntity.ok(toResponse(documentService.updateDocument(tenantId, userId, id, req, file)));
    }

    @PatchMapping(value = "/{id}", consumes = "application/json")
    public ResponseEntity<DocumentResponse> patch(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestBody DocumentService.UpdateDocumentRequest req) throws IOException {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        UUID userId   = UUID.fromString(claims.getSubject());
        return ResponseEntity.ok(toResponse(documentService.updateDocument(tenantId, userId, id, req, null)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        documentService.deleteDocument(tenantId, id);
        return ResponseEntity.noContent().build();
    }

    // ── Versions ──────────────────────────────────────────────────────────

    @GetMapping("/{id}/versions")
    public ResponseEntity<List<VersionResponse>> versions(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return ResponseEntity.ok(
                documentService.listVersions(tenantId, id)
                        .stream().map(v -> new VersionResponse(
                                v.getId(), v.getDocumentId(), v.getVersionNum(),
                                v.getAssetId(), v.getChangeNotes(), v.getCreatedAt()))
                        .toList());
    }

    // ── Comments ──────────────────────────────────────────────────────────

    @GetMapping("/{id}/comments")
    public ResponseEntity<List<CommentResponse>> comments(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return ResponseEntity.ok(
                documentService.listComments(tenantId, id)
                        .stream().map(c -> new CommentResponse(
                                c.getId(), c.getDocumentId(),
                                c.getAuthor() != null ? c.getAuthor().getFullName() : "Unknown",
                                c.getBody(), c.getCreatedAt()))
                        .toList());
    }

    @PostMapping("/{id}/comments")
    public ResponseEntity<CommentResponse> addComment(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestBody CommentRequest req) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        UUID userId   = UUID.fromString(claims.getSubject());
        var comment = documentService.addComment(tenantId, userId, id, req.body());
        return ResponseEntity.ok(new CommentResponse(
                comment.getId(), comment.getDocumentId(),
                comment.getAuthor() != null ? comment.getAuthor().getFullName() : "Unknown",
                comment.getBody(), comment.getCreatedAt()));
    }

    // ── Approval ──────────────────────────────────────────────────────────

    @PostMapping("/{id}/submit")
    public ResponseEntity<ApprovalResponse> submit(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        UUID userId   = UUID.fromString(claims.getSubject());
        var approval = documentService.submitForApproval(tenantId, userId, id);
        return ResponseEntity.ok(toApprovalResponse(approval));
    }

    @GetMapping("/{id}/approvals")
    public ResponseEntity<List<ApprovalResponse>> approvals(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return ResponseEntity.ok(
                documentService.listApprovals(tenantId, id)
                        .stream().map(this::toApprovalResponse).toList());
    }

    @PostMapping("/approvals/{approvalId}/decide")
    public ResponseEntity<Void> decide(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID approvalId,
            @RequestBody DecisionRequest req) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        UUID userId   = UUID.fromString(claims.getSubject());
        documentService.decideStep(tenantId, userId, approvalId, req.decision(), req.comments());
        return ResponseEntity.ok().build();
    }

    // ── Mappers ───────────────────────────────────────────────────────────

    private DocumentResponse toResponse(DocumentEntity d) {
        return new DocumentResponse(
                d.getId(), d.getTitle(), d.getDocType(), d.getDescription(),
                d.getTags(), d.getCurrentVersion(), d.getStatus().name(),
                d.getWorkflowId(), d.getCreatedAt(), d.getUpdatedAt());
    }

    private ApprovalResponse toApprovalResponse(DocumentApprovalEntity a) {
        return new ApprovalResponse(a.getId(), a.getDocumentId(), a.getStatus(),
                a.getCurrentStep(), a.getStartedAt(), a.getCompletedAt());
    }

    // ── DTO records ───────────────────────────────────────────────────────

    public record DocumentResponse(UUID id, String title, String docType, String description,
                                    String[] tags, int currentVersion, String status,
                                    UUID workflowId, LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record VersionResponse(UUID id, UUID documentId, int versionNum,
                                   UUID assetId, String changeNotes, LocalDateTime createdAt) {}

    public record CommentResponse(UUID id, UUID documentId, String authorName,
                                   String body, LocalDateTime createdAt) {}

    public record ApprovalResponse(UUID id, UUID documentId, String status,
                                    int currentStep, LocalDateTime startedAt, LocalDateTime completedAt) {}

    public record CommentRequest(String body) {}

    public record DecisionRequest(String decision, String comments) {}

    public record AuditEventResponse(UUID id, UUID documentId, String eventType,
                                      String actorEmail, java.util.Map<String, Object> payload,
                                      LocalDateTime createdAt) {}
}
