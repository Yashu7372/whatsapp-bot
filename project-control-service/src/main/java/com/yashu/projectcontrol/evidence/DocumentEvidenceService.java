package com.yashu.projectcontrol.evidence;

import com.yashu.projectcontrol.access.ProjectAccessService;
import com.yashu.projectcontrol.document.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.DOCUMENT_VIEW;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.PROJECT_MANAGE;

/**
 * Stores immutable, revision-bound facts produced by a document/drawing extractor.
 *
 * <p>This is intentionally separate from the reasoning worker. Extractors produce
 * evidence; an LLM may later reason over that evidence but cannot rewrite the
 * controlled document, workflow decision or extractor snapshot.</p>
 */
@Service
public class DocumentEvidenceService {

    private final DocumentEvidenceSnapshotRepository repository;
    private final DocumentService documentService;
    private final ProjectAccessService accessService;
    private final ObjectMapper objectMapper;

    public DocumentEvidenceService(
            DocumentEvidenceSnapshotRepository repository,
            DocumentService documentService,
            ProjectAccessService accessService,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.documentService = documentService;
        this.accessService = accessService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public EvidenceView record(
            UUID userId,
            UUID documentId,
            UUID revisionId,
            String extractorCode,
            String extractorVersion,
            String evidenceJson) {
        var document = documentService.get(documentId);
        // Human-facing APIs may only register trusted extractor output through a project administrator.
        // A future machine identity adapter can call the same application service after it has an
        // explicit service-account capability; do not weaken this to DOCUMENT_VIEW.
        accessService.require(userId, PROJECT_MANAGE, document.projectId(), null);
        var revision = requireRevision(documentId, revisionId);
        String normalizedEvidence = validateEvidence(evidenceJson);

        var saved = repository.save(DocumentEvidenceSnapshot.create(
                document.projectId(),
                documentId,
                revisionId,
                normalizeCode(extractorCode, "extractorCode"),
                requireText(extractorVersion, "extractorVersion"),
                revision.contentSha256(),
                normalizedEvidence,
                userId.toString()));
        return toView(saved);
    }

    @Transactional(readOnly = true)
    public Optional<EvidenceView> latest(UUID userId, UUID documentId, UUID revisionId) {
        var document = documentService.get(documentId);
        accessService.require(userId, DOCUMENT_VIEW, document.projectId(), document.primaryScopeId());
        requireRevision(documentId, revisionId);
        return repository.findTopByRevisionIdOrderByCreatedAtDesc(revisionId).map(DocumentEvidenceService::toView);
    }

    private DocumentService.RevisionView requireRevision(UUID documentId, UUID revisionId) {
        return documentService.listRevisions(documentId).stream()
                .filter(revision -> revision.id().equals(revisionId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Document revision not found: " + revisionId));
    }

    private String validateEvidence(String value) {
        String text = requireText(value, "evidenceJson");
        final JsonNode root;
        try {
            root = objectMapper.readTree(text);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "evidenceJson must be valid JSON", ex);
        }
        if (root == null || !root.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "evidenceJson must be a JSON object");
        }
        return text;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }

    private static String normalizeCode(String value, String field) {
        return requireText(value, field).toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static EvidenceView toView(DocumentEvidenceSnapshot snapshot) {
        return new EvidenceView(
                snapshot.getId(),
                snapshot.getProjectId(),
                snapshot.getDocumentId(),
                snapshot.getRevisionId(),
                snapshot.getExtractorCode(),
                snapshot.getExtractorVersion(),
                snapshot.getInputContentSha256(),
                snapshot.getEvidenceJson(),
                snapshot.getCreatedByReference(),
                snapshot.getCreatedAt());
    }

    public record EvidenceView(
            UUID id,
            UUID projectId,
            UUID documentId,
            UUID revisionId,
            String extractorCode,
            String extractorVersion,
            String inputContentSha256,
            String evidenceJson,
            String createdByReference,
            Instant createdAt) {
    }
}
