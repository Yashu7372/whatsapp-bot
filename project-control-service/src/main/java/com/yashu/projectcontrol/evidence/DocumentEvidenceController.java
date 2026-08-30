package com.yashu.projectcontrol.evidence;

import com.yashu.projectcontrol.access.ProjectControlPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents/{documentId}/revisions/{revisionId}/evidence-snapshots")
public class DocumentEvidenceController {

    private final DocumentEvidenceService service;

    public DocumentEvidenceController(DocumentEvidenceService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentEvidenceService.EvidenceView record(
            @PathVariable UUID documentId,
            @PathVariable UUID revisionId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @Valid @RequestBody RecordEvidenceRequest request) {
        return service.record(
                principal.userId(), documentId, revisionId,
                request.extractorCode(), request.extractorVersion(), request.evidenceJson());
    }

    @GetMapping("/latest")
    public Optional<DocumentEvidenceService.EvidenceView> latest(
            @PathVariable UUID documentId,
            @PathVariable UUID revisionId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        return service.latest(principal.userId(), documentId, revisionId);
    }

    public record RecordEvidenceRequest(
            @NotBlank @Size(max = 100) String extractorCode,
            @NotBlank @Size(max = 100) String extractorVersion,
            @NotBlank String evidenceJson) {
    }
}
