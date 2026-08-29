package com.yashu.projectcontrol.document;

import com.yashu.projectcontrol.organization.OrganizationService;
import com.yashu.projectcontrol.project.ProjectService;
import com.yashu.projectcontrol.scope.ScopeService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentRevisionRepository revisionRepository;
    private final DocumentNumberService numberService;
    private final ProjectService projectService;
    private final ScopeService scopeService;
    private final OrganizationService organizationService;

    public DocumentService(
            DocumentRepository documentRepository,
            DocumentRevisionRepository revisionRepository,
            DocumentNumberService numberService,
            ProjectService projectService,
            ScopeService scopeService,
            OrganizationService organizationService) {
        this.documentRepository = documentRepository;
        this.revisionRepository = revisionRepository;
        this.numberService = numberService;
        this.projectService = projectService;
        this.scopeService = scopeService;
        this.organizationService = organizationService;
    }

    @Transactional
    public DocumentView create(
            UUID projectId,
            UUID primaryScopeId,
            UUID originatorOrganizationId,
            String requestedDocumentNumber,
            String numberSeriesCode,
            String documentType,
            String title,
            String description,
            String classificationCode,
            String metadataJson) {
        projectService.requireExists(projectId);
        if (primaryScopeId != null) {
            scopeService.requireExistsInProject(projectId, primaryScopeId);
        }
        if (originatorOrganizationId != null) {
            organizationService.requireExists(originatorOrganizationId);
        }

        String normalizedType = normalizeCode(documentType, "documentType");
        String normalizedTitle = requireText(title, "title");

        DocumentNumberSource numberSource;
        String normalizedSeriesCode;
        String documentNumber;
        if (requestedDocumentNumber == null || requestedDocumentNumber.isBlank()) {
            normalizedSeriesCode = normalizeCode(numberSeriesCode, "numberSeriesCode");
            documentNumber = numberService.nextReference(projectId, normalizedSeriesCode, normalizedType);
            numberSource = DocumentNumberSource.GENERATED;
        } else {
            normalizedSeriesCode = null;
            documentNumber = normalizeDocumentNumber(requestedDocumentNumber);
            numberSource = DocumentNumberSource.EXTERNAL;
        }

        if (documentRepository.existsByProjectIdAndDocumentNumberIgnoreCase(projectId, documentNumber)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Document number already exists in project: " + documentNumber);
        }

        Document document = Document.create(
                projectId,
                primaryScopeId,
                originatorOrganizationId,
                documentNumber,
                numberSource,
                normalizedSeriesCode,
                normalizedType,
                normalizedTitle,
                normalizeOptional(description),
                normalizeOptionalUpper(classificationCode),
                normalizeJson(metadataJson));

        return toView(documentRepository.save(document));
    }

    @Transactional(readOnly = true)
    public DocumentView get(UUID documentId) {
        return toView(requireDocument(documentId));
    }

    @Transactional(readOnly = true)
    public List<DocumentView> listByProject(UUID projectId) {
        projectService.requireExists(projectId);
        return documentRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .map(DocumentService::toView)
                .toList();
    }

    @Transactional
    public RevisionView addRevision(
            UUID documentId,
            String revisionCode,
            String changeNotes,
            String contentUri,
            String contentSha256,
            String originalFilename,
            String mediaType,
            Long sizeBytes) {
        Document document = documentRepository.lockById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: " + documentId));

        if (sizeBytes != null && sizeBytes < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sizeBytes cannot be negative");
        }
        String normalizedSha = normalizeSha256(contentSha256);
        int sequence = document.getCurrentRevisionSequence() + 1;
        String normalizedRevisionCode = revisionCode == null || revisionCode.isBlank()
                ? String.format("%02d", sequence)
                : normalizeCode(revisionCode, "revisionCode");

        if (revisionRepository.existsByDocumentIdAndRevisionCodeIgnoreCase(documentId, normalizedRevisionCode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Revision code already exists for document: " + normalizedRevisionCode);
        }

        DocumentRevision revision = revisionRepository.save(DocumentRevision.create(
                documentId,
                document.getProjectId(),
                sequence,
                normalizedRevisionCode,
                normalizeOptional(changeNotes),
                normalizeOptional(contentUri),
                normalizedSha,
                normalizeOptional(originalFilename),
                normalizeOptional(mediaType),
                sizeBytes));

        document.advanceRevision(sequence, normalizedRevisionCode);
        documentRepository.save(document);
        return toRevisionView(revision);
    }

    @Transactional(readOnly = true)
    public List<RevisionView> listRevisions(UUID documentId) {
        requireDocument(documentId);
        return revisionRepository.findByDocumentIdOrderBySequenceNumberAsc(documentId).stream()
                .map(DocumentService::toRevisionView)
                .toList();
    }

    private Document requireDocument(UUID id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: " + id));
    }

    private static String normalizeDocumentNumber(String value) {
        return requireText(value, "documentNumber").toUpperCase(Locale.ROOT);
    }

    private static String normalizeCode(String value, String field) {
        return requireText(value, field).toUpperCase(Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeOptionalUpper(String value) {
        String normalized = normalizeOptional(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static String normalizeJson(String value) {
        return value == null || value.isBlank() ? "{}" : value.trim();
    }

    private static String normalizeSha256(String value) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            return null;
        }
        if (!normalized.matches("[0-9a-fA-F]{64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contentSha256 must contain exactly 64 hexadecimal characters");
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static DocumentView toView(Document document) {
        return new DocumentView(
                document.getId(),
                document.getProjectId(),
                document.getPrimaryScopeId(),
                document.getOriginatorOrganizationId(),
                document.getDocumentNumber(),
                document.getNumberSource().name(),
                document.getNumberSeriesCode(),
                document.getDocumentType(),
                document.getTitle(),
                document.getDescription(),
                document.getClassificationCode(),
                document.getMetadataJson(),
                document.getStatus().name(),
                document.getCurrentRevisionSequence(),
                document.getCurrentRevisionCode(),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }

    private static RevisionView toRevisionView(DocumentRevision revision) {
        return new RevisionView(
                revision.getId(),
                revision.getDocumentId(),
                revision.getProjectId(),
                revision.getSequenceNumber(),
                revision.getRevisionCode(),
                revision.getRevisionStatus().name(),
                revision.getChangeNotes(),
                revision.getContentUri(),
                revision.getContentSha256(),
                revision.getOriginalFilename(),
                revision.getMediaType(),
                revision.getSizeBytes(),
                revision.getCreatedAt());
    }

    public record DocumentView(
            UUID id,
            UUID projectId,
            UUID primaryScopeId,
            UUID originatorOrganizationId,
            String documentNumber,
            String numberSource,
            String numberSeriesCode,
            String documentType,
            String title,
            String description,
            String classificationCode,
            String metadataJson,
            String status,
            int currentRevisionSequence,
            String currentRevisionCode,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record RevisionView(
            UUID id,
            UUID documentId,
            UUID projectId,
            int sequenceNumber,
            String revisionCode,
            String revisionStatus,
            String changeNotes,
            String contentUri,
            String contentSha256,
            String originalFilename,
            String mediaType,
            Long sizeBytes,
            Instant createdAt) {
    }
}
