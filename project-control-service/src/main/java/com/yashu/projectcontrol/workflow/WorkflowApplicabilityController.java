package com.yashu.projectcontrol.workflow;

import com.yashu.projectcontrol.access.ProjectAccessService;
import com.yashu.projectcontrol.access.ProjectControlPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.PROJECT_VIEW;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.SCOPE_VIEW;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class WorkflowApplicabilityController {

    private final WorkflowApplicabilityService service;
    private final ProjectAccessService accessService;

    public WorkflowApplicabilityController(
            WorkflowApplicabilityService service,
            ProjectAccessService accessService) {
        this.service = service;
        this.accessService = accessService;
    }

    @GetMapping("/scopes/{scopeId}/available-workflow-definitions")
    public List<WorkflowService.DefinitionView> availableForScope(
            @PathVariable UUID projectId,
            @PathVariable UUID scopeId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        accessService.require(principal.userId(), SCOPE_VIEW, projectId, scopeId);
        return service.listAvailableDefinitions(projectId, scopeId);
    }

    @GetMapping("/workflow-bindings")
    public List<WorkflowService.BindingView> projectBindings(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        accessService.require(principal.userId(), PROJECT_VIEW, projectId, null);
        return service.listProjectBindings(projectId);
    }

    @GetMapping("/workflow-definitions/{definitionId}/bindings")
    public List<WorkflowService.BindingView> definitionBindings(
            @PathVariable UUID projectId,
            @PathVariable UUID definitionId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        accessService.require(principal.userId(), PROJECT_VIEW, projectId, null);
        return service.listDefinitionBindings(projectId, definitionId);
    }
}
