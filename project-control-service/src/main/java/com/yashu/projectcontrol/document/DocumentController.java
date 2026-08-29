package com.yashu.projectcontrol.document;

import com.yashu.projectcontrol.access.ProjectAccessService;
import com.yashu.projectcontrol.access.ProjectControlPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.DOCUMENT_SUBMIT;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.DOCUMENT_VIEW;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.PROJECT_MANAGE;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.PROJECT_VIEW;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessOutcome.ALLOW;

@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentNumberService numberService;
    private final ProjectAccessService accessService;

    public DocumentController(
            DocumentService documentService,
            DocumentNumberService numberService,
            ProjectAccessService accessService) {
        this.documentService = documentService;
        this.numberService = numberService;
        this.accessService = accessService;
    }

    @PostMapping("/projects/{projectId}/document-number-series")
    public ResponseEntity<DocumentNumberService.SeriesView> defineSeries(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody DefineSeriesRequest request) {
        accessService.require(principal.userId(), PROJECT_MANAGE, projectId, null);
        return ResponseEntity.ok(numberService.defineSeries(
                projectId,
                request.seriesCode(),
                request.documentType(),
                request.prefix(),
                request.padding(),
                request.separator()));
    }

    @GetMapping("/projects/{projectId}/document-number-series")
    public ResponseEntity<List<DocumentNumberService.SeriesView>> listSeries(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        accessService.require(principal.userId(), PROJECT_VIEW, projectId, null);
        return ResponseEntity.ok(numberService.listSeries(projectId));
    }

    @PostMapping("/projects/{projectId}/documents")
    public ResponseEntity<DocumentService.DocumentView> create(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody CreateDocumentRequest request) {
        UUID userId = principal.userId();
        accessService.require(userId, DOCUMENT_SUBMIT, projectId, request.primaryScopeId());
        accessService.requireCanRepresentOrganization(userId, projectId, request.originatorOrganizationId());
        return ResponseEntity.ok(documentService.create(
                projectId,
                request.primaryScopeId(),
                request.originatorOrganizationId(),
                request.documentNumber(),
                request.numberSeriesCode(),
                request.documentType(),
                request.title(),
                request.description(),
                request.classificationCode(),
                request.metadataJson()));
    }

    @GetMapping("/projects/{projectId}/documents")
    public ResponseEntity<List<DocumentService.DocumentView>> list(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        UUID userId = principal.userId();
        accessService.require(userId, PROJECT_VIEW, projectId, null);
        List<DocumentService.DocumentView> visible = documentService.listByProject(projectId).stream()
                .filter(document -> accessService.decide(
                        userId, DOCUMENT_VIEW, projectId, document.primaryScopeId()).outcome() == ALLOW)
                .toList();
        return ResponseEntity.ok(visible);
    }

    @GetMapping("/documents/{documentId}")
    public ResponseEntity<DocumentService.DocumentView> get(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        var document = documentService.get(documentId);
        accessService.require(principal.userId(), DOCUMENT_VIEW, document.projectId(), document.primaryScopeId());
        return ResponseEntity.ok(document);
    }

    @PostMapping("/documents/{documentId}/revisions")
    public ResponseEntity<DocumentService.RevisionView> addRevision(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody AddRevisionRequest request) {
        var document = documentService.get(documentId);
        accessService.require(principal.userId(), DOCUMENT_SUBMIT, document.projectId(), document.primaryScopeId());
        return ResponseEntity.ok(documentService.addRevision(
                documentId,
                request.revisionCode(),
                request.changeNotes(),
                request.contentUri(),
                request.contentSha256(),
                request.originalFilename(),
                request.mediaType(),
                request.sizeBytes()));
    }

    @GetMapping("/documents/{documentId}/revisions")
    public ResponseEntity<List<DocumentService.RevisionView>> revisions(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        var document = documentService.get(documentId);
        accessService.require(principal.userId(), DOCUMENT_VIEW, document.projectId(), document.primaryScopeId());
        return ResponseEntity.ok(documentService.listRevisions(documentId));
    }

    public record DefineSeriesRequest(
            String seriesCode,
            String documentType,
            String prefix,
            Integer padding,
            String separator) {}

    public record CreateDocumentRequest(
            UUID primaryScopeId,
            UUID originatorOrganizationId,
            String documentNumber,
            String numberSeriesCode,
            String documentType,
            String title,
            String description,
            String classificationCode,
            String metadataJson) {}

    public record AddRevisionRequest(
            String revisionCode,
            String changeNotes,
            String contentUri,
            String contentSha256,
            String originalFilename,
            String mediaType,
            Long sizeBytes) {}
}
