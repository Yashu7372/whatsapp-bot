package com.yashu.projectcontrol.document;

import com.yashu.projectcontrol.access.AccessController;
import com.yashu.projectcontrol.access.ProjectAccessService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.UUID;

import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.DOCUMENT_CONTENT_VIEW;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.DOCUMENT_SUBMIT;

@RestController
@RequestMapping("/api/v1")
public class DocumentContentController {

    private final DocumentService documentService;
    private final DocumentRevisionRepository revisionRepository;
    private final LocalDocumentContentStore contentStore;
    private final ProjectAccessService accessService;

    public DocumentContentController(
            DocumentService documentService,
            DocumentRevisionRepository revisionRepository,
            LocalDocumentContentStore contentStore,
            ProjectAccessService accessService) {
        this.documentService = documentService;
        this.revisionRepository = revisionRepository;
        this.contentStore = contentStore;
        this.accessService = accessService;
    }

    @PostMapping(value = "/documents/{documentId}/revisions/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentService.RevisionView uploadRevision(
            @PathVariable UUID documentId,
            @RequestHeader(AccessController.ACTOR_HEADER) UUID userId,
            @RequestParam String revisionCode,
            @RequestParam(required = false) String changeNotes,
            @RequestPart("file") MultipartFile file) {
        var document = documentService.get(documentId);
        accessService.require(userId, DOCUMENT_SUBMIT, document.projectId(), document.primaryScopeId());

        LocalDocumentContentStore.StoredContent stored;
        try {
            stored = contentStore.storePdf(file.getBytes(), file.getOriginalFilename());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded PDF", ex);
        }

        try {
            return documentService.addRevision(
                    documentId,
                    revisionCode,
                    changeNotes,
                    stored.contentUri(),
                    stored.sha256(),
                    stored.originalFilename(),
                    stored.mediaType(),
                    stored.sizeBytes());
        } catch (RuntimeException ex) {
            contentStore.deleteQuietly(stored.contentUri());
            throw ex;
        }
    }

    @GetMapping("/document-revisions/{revisionId}/content")
    public ResponseEntity<byte[]> viewPdf(
            @PathVariable UUID revisionId,
            @RequestHeader(AccessController.ACTOR_HEADER) UUID userId) {
        DocumentRevision revision = revisionRepository.findById(revisionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Document revision not found: " + revisionId));
        var document = documentService.get(revision.getDocumentId());
        accessService.require(userId, DOCUMENT_CONTENT_VIEW, document.projectId(), document.primaryScopeId());
        byte[] bytes = contentStore.read(revision.getContentUri());
        String filename = revision.getOriginalFilename() == null ? "document.pdf" : revision.getOriginalFilename();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(filename).build().toString())
                .contentLength(bytes.length)
                .body(bytes);
    }
}
