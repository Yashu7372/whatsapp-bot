package com.whatsappbot.document;

import com.whatsappbot.features.FeatureAccessService;
import com.whatsappbot.features.FeatureCode;
import com.whatsappbot.project.ProjectAccessService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentAuditService documentAuditService;
    private final FeatureAccessService featureAccessService;
    private final ProjectAccessService projectAccessService;
    private final EncryptedDocumentMetadataValidator encryptedMetadataValidator;

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<DocumentResponse> create(
            @AuthenticationPrincipal Claims claims,
            @RequestPart("title") String title,
            @RequestPart(value = "docType", required = false) String docType,
            @RequestPart(value = "description", required = false) String description,
            @RequestPart(value = "projectId", required = false) String projectId,
            @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {

        UUID tenantId = tenantId(claims);
        UUID userId = userId(claims);
        assertDocumentAccess(tenantId);
        var actor = projectAccessService.requireActiveUser(tenantId, userId);

        UUID parsedProjectId = projectId != null && !projectId.isBlank()
                ? UUID.fromString(projectId)
                : null;
        if (parsedProjectId != null) {
            projectAccessService.requireProjectVisibility(tenantId, parsedProjectId, actor);
        }

        var req = new DocumentService.CreateDocumentRequest(
                title, docType, description, null, parsedProjectId);
        DocumentEntity doc = documentService.createDocument(tenantId, userId, req, file);
        return ResponseEntity.ok(toResponse(doc));
    }

    // ── Zero-knowledge encrypted upload ───────────────────────────────────

    @PostMapping(value = "/encrypted", consumes = "multipart/form-data")
    public ResponseEntity<DocumentResponse> createEncrypted(
            @AuthenticationPrincipal Claims claims,
            @RequestPart("metadata") String metadataJson,
            @RequestPart(value = "encryptedFile", required = false) MultipartFile encryptedFile)
            throws IOException {

        UUID tenantId = tenantId(claims);
        UUID userId = userId(claims);

        assertDocumentAccess(tenantId);
        featureAccessService.assertFeatureEnabled(tenantId, FeatureCode.ZERO_KNOWLEDGE_STORAGE);
        projectAccessService.requireActiveUser(tenantId, userId);

        // Validate all metadata that can deterministically fail before StorageService is called.
        // Object storage is outside the database transaction, so discovering a missing IV/hash
        // only after writing the ciphertext leaves an orphan object when the DB rolls back.
        encryptedMetadataValidator.validate(metadataJson, encryptedFile);

        DocumentEntity doc = documentService.createEncryptedDocument(
                tenantId, userId, metadataJson, encryptedFile);
        return ResponseEntity.ok(toResponse(doc));
    }

    // ── Audit trail ───────────────────────────────────────────────────────

    @GetMapping("/{id}/audit")
    public ResponseEntity<List<DocumentAuditService.AuditEventView>> auditTrail(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = tenantId(claims);
        UUID userId = userId(claims);
        assertDocumentAccess(tenantId);
        requireDocumentAccess(tenantId, userId, id);
        return ResponseEntity.ok(documentAuditService.getAuditTrail(tenantId, id));
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<DocumentResponse> createJson(
            @AuthenticationPrincipal Claims claims,
            @RequestBody DocumentService.CreateDocumentRequest req) throws IOException {

        UUID tenantId = tenantId(claims);
        UUID userId = userId(claims);
        assertDocumentAccess(tenantId);
        var actor = projectAccessService.requireActiveUser(tenantId, userId);
        if (req.projectId() != null) {
            projectAccessService.requireProjectVisibility(tenantId, req.projectId(), actor);
        }

        DocumentEntity doc = documentService.createDocument(tenantId, userId, req, null);
        return ResponseEntity.ok(toResponse(doc));
    }

    @GetMapping
    public ResponseEntity<List<DocumentResponse>> list(
            @AuthenticationPrincipal Claims claims,
            @RequestParam(required = false) String docType) {

        UUID tenantId = tenantId(claims);
        UUID userId = userId(claims);
        assertDocumentAccess(tenantId);
        return ResponseEntity.ok(
                documentService.listDocuments(tenantId, userId, docType)
                        .stream().map(this::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> get(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = tenantId(claims);
        UUID userId = userId(claims);
        assertDocumentAccess(tenantId);
        return ResponseEntity.ok(toResponse(documentService.getDocument(tenantId, userId, id)));
    }

    @PutMapping(value = "/{id}", consumes = "multipart/form-data")
    public ResponseEntity<DocumentResponse> update(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestPart(value = "title", required = false) String title,
            @RequestPart(value = "description", required = false) String description,
            @RequestPart(value = "changeNotes", required = false) String changeNotes,
            @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {

        UUID tenantId = tenantId(claims);
        UUID userId = userId(claims);
        assertDocumentAccess(tenantId);
        requireDocumentAccess(tenantId, userId, id);

        var req = new DocumentService.UpdateDocumentRequest(title, description, null, changeNotes);
        return ResponseEntity.ok(toResponse(
                documentService.updateDocument(tenantId, userId, id, req, file)));
    }

    @PatchMapping(value = "/{id}", consumes = "application/json")
    public ResponseEntity<DocumentResponse> patch(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestBody DocumentService.UpdateDocumentRequest req) throws IOException {

        UUID tenantId = tenantId(claims);
        UUID userId = userId(claims);
        assertDocumentAccess(tenantId);
        requireDocumentAccess(tenantId, userId, id);
        return ResponseEntity.ok(toResponse(
                documentService.updateDocument(tenantId, userId, id, req, null)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = tenantId(claims);
        UUID userId = userId(claims);
        assertDocumentAccess(tenantId);
        requireDocumentAccess(tenantId, userId, id);
        documentService.deleteDocument(tenantId, id);
        return ResponseEntity.noContent().build();
    }

    // ── Versions ──────────────────────────────────────────────────────────

    @GetMapping("/{id}/versions")
    public ResponseEntity<List<VersionResponse>> versions(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = tenantId(claims);
        UUID userId = userId(claims);
        assertDocumentAccess(tenantId);
        requireDocumentAccess(tenantId, userId, id);
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

        UUID tenantId = tenantId(claims);
        UUID userId = userId(claims);
        assertDocumentAccess(tenantId);
        requireDocumentAccess(tenantId, userId, id);
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

        UUID tenantId = tenantId(claims);
        UUID userId = userId(claims);
        assertDocumentAccess(tenantId);
        requireDocumentAccess(tenantId, userId, id);
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

        UUID tenantId = tenantId(claims);
        UUID userId = userId(claims);
        assertDocumentAccess(tenantId);
        requireDocumentAccess(tenantId, userId, id);
        var approval = documentService.submitForApproval(tenantId, userId, id);
        return ResponseEntity.ok(toApprovalResponse(approval));
    }

    @GetMapping("/{id}/approvals")
    public ResponseEntity<List<ApprovalResponse>> approvals(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = tenantId(claims);
        UUID userId = userId(claims);
        assertDocumentAccess(tenantId);
        requireDocumentAccess(tenantId, userId, id);
        return ResponseEntity.ok(
                documentService.listApprovals(tenantId, id)
                        .stream().map(this::toApprovalResponse).toList());
    }

    @PostMapping("/approvals/{approvalId}/decide")
    public ResponseEntity<Void> decide(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID approvalId,
            @RequestBody DecisionRequest req) {

        UUID tenantId = tenantId(claims);
        UUID userId = userId(claims);
        assertDocumentAccess(tenantId);
        // decideStep performs the stronger approval-specific checks: active tenant membership,
        // assigned-reviewer identity and the pessimistic lock around the state transition.
        documentService.decideStep(tenantId, userId, approvalId, req.decision(), req.comments(),
                req.reviewOutcome());
        return ResponseEntity.ok().build();
    }

    // ── Access helpers ─────────────────────────────────────────────────────

    private void assertDocumentAccess(UUID tenantId) {
        featureAccessService.assertAccess(tenantId, FeatureCode.DOCUMENT_CONTROL);
    }

    private void requireDocumentAccess(UUID tenantId, UUID userId, UUID documentId) {
        projectAccessService.requireActiveUser(tenantId, userId);
        // The caller-aware service read enforces project participation for project documents.
        documentService.getDocument(tenantId, userId, documentId);
    }

    private static UUID tenantId(Claims claims) {
        return UUID.fromString((String) claims.get("tenantId"));
    }

    private static UUID userId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    // ── Mappers ───────────────────────────────────────────────────────────

    private DocumentResponse toResponse(DocumentEntity d) {
        return new DocumentResponse(
                d.getId(), d.getTitle(), d.getDocType(), d.getDescription(),
                d.getTags(), d.getCurrentVersion(), d.getStatus().name(),
                d.getWorkflowId(), d.getProjectId(), d.getOriginatorOrgId(), d.getDocumentCode(),
                d.getDueAt(), d.getReviewOutcome(),
                d.getCreatedAt(), d.getUpdatedAt());
    }

    private ApprovalResponse toApprovalResponse(DocumentApprovalEntity a) {
        return new ApprovalResponse(a.getId(), a.getDocumentId(), a.getStatus(),
                a.getCurrentStep(), a.getStartedAt(), a.getCompletedAt());
    }

    // ── DTO records ───────────────────────────────────────────────────────

    public record DocumentResponse(UUID id, String title, String docType, String description,
                                    String[] tags, int currentVersion, String status,
                                    UUID workflowId, UUID projectId, UUID originatorOrgId,
                                    String documentCode, LocalDateTime dueAt,
                                    ReviewOutcome reviewOutcome,
                                    LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record VersionResponse(UUID id, UUID documentId, int versionNum,
                                   UUID assetId, String changeNotes, LocalDateTime createdAt) {}

    public record CommentResponse(UUID id, UUID documentId, String authorName,
                                   String body, LocalDateTime createdAt) {}

    public record ApprovalResponse(UUID id, UUID documentId, String status,
                                    int currentStep, LocalDateTime startedAt, LocalDateTime completedAt) {}

    public record CommentRequest(String body) {}

    /**
     * {@code reviewOutcome} carries the contractual return code (CODE_A..CODE_D). When present it
     * decides the outcome; {@code decision} remains accepted for callers that do not use codes.
     */
    public record DecisionRequest(String decision, String comments, ReviewOutcome reviewOutcome) {}
}
