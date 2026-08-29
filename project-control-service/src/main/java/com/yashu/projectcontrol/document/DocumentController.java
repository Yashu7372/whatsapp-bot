package com.yashu.projectcontrol.document;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentNumberService numberService;

    public DocumentController(DocumentService documentService, DocumentNumberService numberService) {
        this.documentService = documentService;
        this.numberService = numberService;
    }

    @PostMapping("/projects/{projectId}/document-number-series")
    public ResponseEntity<DocumentNumberService.SeriesView> defineSeries(
            @PathVariable UUID projectId,
            @RequestBody DefineSeriesRequest request) {
        return ResponseEntity.ok(numberService.defineSeries(
                projectId,
                request.seriesCode(),
                request.documentType(),
                request.prefix(),
                request.padding(),
                request.separator()));
    }

    @GetMapping("/projects/{projectId}/document-number-series")
    public ResponseEntity<List<DocumentNumberService.SeriesView>> listSeries(@PathVariable UUID projectId) {
        return ResponseEntity.ok(numberService.listSeries(projectId));
    }

    @PostMapping("/projects/{projectId}/documents")
    public ResponseEntity<DocumentService.DocumentView> create(
            @PathVariable UUID projectId,
            @RequestBody CreateDocumentRequest request) {
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
    public ResponseEntity<List<DocumentService.DocumentView>> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok(documentService.listByProject(projectId));
    }

    @GetMapping("/documents/{documentId}")
    public ResponseEntity<DocumentService.DocumentView> get(@PathVariable UUID documentId) {
        return ResponseEntity.ok(documentService.get(documentId));
    }

    @PostMapping("/documents/{documentId}/revisions")
    public ResponseEntity<DocumentService.RevisionView> addRevision(
            @PathVariable UUID documentId,
            @RequestBody AddRevisionRequest request) {
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
    public ResponseEntity<List<DocumentService.RevisionView>> revisions(@PathVariable UUID documentId) {
        return ResponseEntity.ok(documentService.listRevisions(documentId));
    }

    public record DefineSeriesRequest(
            String seriesCode,
            String documentType,
            String prefix,
            Integer padding,
            String separator) {
    }

    public record CreateDocumentRequest(
            UUID primaryScopeId,
            UUID originatorOrganizationId,
            String documentNumber,
            String numberSeriesCode,
            String documentType,
            String title,
            String description,
            String classificationCode,
            String metadataJson) {
    }

    public record AddRevisionRequest(
            String revisionCode,
            String changeNotes,
            String contentUri,
            String contentSha256,
            String originalFilename,
            String mediaType,
            Long sizeBytes) {
    }
}
