package com.yashu.projectcontrol.document;

import com.yashu.projectcontrol.access.ProjectAccessService;
import com.yashu.projectcontrol.access.ProjectControlPrincipal;
import com.yashu.projectcontrol.workflow.WorkflowApplicabilityService;
import com.yashu.projectcontrol.workflow.WorkflowService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.DOCUMENT_VIEW;

@RestController
@RequestMapping("/api/v1/documents/{documentId}/available-workflow-definitions")
public class DocumentWorkflowApplicabilityController {

    private final DocumentService documentService;
    private final WorkflowApplicabilityService applicabilityService;
    private final ProjectAccessService accessService;

    public DocumentWorkflowApplicabilityController(
            DocumentService documentService,
            WorkflowApplicabilityService applicabilityService,
            ProjectAccessService accessService) {
        this.documentService = documentService;
        this.applicabilityService = applicabilityService;
        this.accessService = accessService;
    }

    @GetMapping
    public List<WorkflowService.DefinitionView> list(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        var document = documentService.get(documentId);
        if (document.primaryScopeId() == null) {
            return List.of();
        }
        accessService.require(
                principal.userId(), DOCUMENT_VIEW, document.projectId(), document.primaryScopeId());
        return applicabilityService.listAvailableDefinitions(
                document.projectId(), document.primaryScopeId());
    }
}
