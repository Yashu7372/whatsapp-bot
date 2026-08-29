package com.yashu.projectcontrol.document;

import com.yashu.projectcontrol.access.ProjectControlPrincipal;
import com.yashu.projectcontrol.workflow.WorkflowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents/{documentId}/workflow-instances")
public class DocumentWorkflowController {

    private final DocumentWorkflowService service;

    public DocumentWorkflowController(DocumentWorkflowService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowService.InstanceView start(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @Valid @RequestBody StartRequest request) {
        return service.startForDocument(
                principal.userId(),
                documentId,
                request.workflowDefinitionId(),
                request.businessKey(),
                request.title(),
                request.contextJson());
    }

    @GetMapping
    public List<WorkflowService.InstanceView> list(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        return service.listForDocument(principal.userId(), documentId);
    }

    public record StartRequest(
            @NotNull UUID workflowDefinitionId,
            @NotBlank @Size(max = 160) String businessKey,
            @NotBlank @Size(max = 500) String title,
            String contextJson) {}
}
