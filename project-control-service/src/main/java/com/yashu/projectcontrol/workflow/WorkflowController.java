package com.yashu.projectcontrol.workflow;

import com.yashu.projectcontrol.access.ProjectAccessService;
import com.yashu.projectcontrol.access.ProjectControlPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.PROJECT_MANAGE;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.PROJECT_VIEW;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.SCOPE_VIEW;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.WORKFLOW_CONFIGURE;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.WORKFLOW_START;

@RestController
@RequestMapping("/api/v1")
public class WorkflowController {

    private final WorkflowService service;
    private final AuthorizedWorkflowExecutionService executionService;
    private final WorkflowDefinitionRepository definitionRepository;
    private final ProjectAccessService accessService;

    public WorkflowController(
            WorkflowService service,
            AuthorizedWorkflowExecutionService executionService,
            WorkflowDefinitionRepository definitionRepository,
            ProjectAccessService accessService) {
        this.service = service;
        this.executionService = executionService;
        this.definitionRepository = definitionRepository;
        this.accessService = accessService;
    }

    @PostMapping("/projects/{projectId}/workflow-definitions")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowService.DefinitionView createDefinition(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @Valid @RequestBody CreateDefinitionRequest request) {
        accessService.require(principal.userId(), PROJECT_MANAGE, projectId, null);
        return service.createDefinition(
                projectId, request.code(), request.version(), request.name(),
                request.purposeCode(), request.requiredCapabilityCode());
    }

    @GetMapping("/projects/{projectId}/workflow-definitions")
    public List<WorkflowService.DefinitionView> listDefinitions(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        accessService.require(principal.userId(), PROJECT_VIEW, projectId, null);
        return service.listDefinitions(projectId);
    }

    @PostMapping("/workflow-definitions/{definitionId}/steps")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowService.StepDefinitionView addStep(
            @PathVariable UUID definitionId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @Valid @RequestBody AddStepRequest request) {
        UUID projectId = definitionProject(definitionId);
        accessService.require(principal.userId(), PROJECT_MANAGE, projectId, null);
        return service.addStep(
                definitionId, request.sequence(), request.stepCode(), request.name(),
                request.completionActionCode(), request.assignmentJson(), request.configurationJson());
    }

    @GetMapping("/workflow-definitions/{definitionId}/steps")
    public List<WorkflowService.StepDefinitionView> listSteps(
            @PathVariable UUID definitionId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        UUID projectId = definitionProject(definitionId);
        accessService.require(principal.userId(), PROJECT_VIEW, projectId, null);
        return service.listDefinitionSteps(definitionId);
    }

    @PostMapping("/workflow-definitions/{definitionId}/activate")
    public WorkflowService.DefinitionView activate(
            @PathVariable UUID definitionId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        UUID projectId = definitionProject(definitionId);
        accessService.require(principal.userId(), PROJECT_MANAGE, projectId, null);
        return service.activateDefinition(definitionId);
    }

    @PutMapping("/projects/{projectId}/scopes/{scopeId}/workflow-bindings/{definitionId}")
    public WorkflowService.BindingView bindToScope(
            @PathVariable UUID projectId,
            @PathVariable UUID scopeId,
            @PathVariable UUID definitionId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @Valid @RequestBody ConfigureBindingRequest request) {
        accessService.require(principal.userId(), WORKFLOW_CONFIGURE, projectId, scopeId);
        return service.setScopeBinding(
                projectId, scopeId, definitionId, request.enabled(), request.configurationJson());
    }

    @GetMapping("/projects/{projectId}/scopes/{scopeId}/workflow-bindings")
    public List<WorkflowService.BindingView> listBindings(
            @PathVariable UUID projectId,
            @PathVariable UUID scopeId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        accessService.require(principal.userId(), SCOPE_VIEW, projectId, scopeId);
        return service.listScopeBindings(projectId, scopeId);
    }

    @PostMapping("/projects/{projectId}/scopes/{scopeId}/workflow-instances")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowService.InstanceView start(
            @PathVariable UUID projectId,
            @PathVariable UUID scopeId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @Valid @RequestBody StartWorkflowRequest request) {
        accessService.require(principal.userId(), WORKFLOW_START, projectId, scopeId);
        return service.start(
                projectId, scopeId, request.workflowDefinitionId(), request.businessKey(),
                request.title(), principal.userId().toString(), request.contextJson());
    }

    @PostMapping("/workflow-instances/{instanceId}/actions")
    public WorkflowService.InstanceView act(
            @PathVariable UUID instanceId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @Valid @RequestBody WorkflowActionRequest request) {
        return executionService.act(
                principal.userId(), instanceId,
                request.actionType(), request.actionCode(), request.targetStepCode(),
                request.comment(), request.metadataJson());
    }

    @GetMapping("/workflow-instances/{instanceId}")
    public WorkflowService.InstanceView get(
            @PathVariable UUID instanceId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        var instance = service.getInstance(instanceId);
        String assignmentJson = instance.currentStep() == null ? "{}" : instance.currentStep().assignmentJson();
        accessService.requireWorkflowStepView(
                principal.userId(), instance.projectId(), instance.scopeId(), assignmentJson);
        return instance;
    }

    @GetMapping("/workflow-instances/{instanceId}/history")
    public WorkflowService.HistoryView history(
            @PathVariable UUID instanceId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        var instance = service.getInstance(instanceId);
        String assignmentJson = instance.currentStep() == null ? "{}" : instance.currentStep().assignmentJson();
        accessService.requireWorkflowStepView(
                principal.userId(), instance.projectId(), instance.scopeId(), assignmentJson);
        return service.history(instanceId);
    }

    private UUID definitionProject(UUID definitionId) {
        return definitionRepository.findById(definitionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow definition not found: " + definitionId))
                .getProjectId();
    }

    public record CreateDefinitionRequest(
            @NotBlank @Size(max = 100) String code,
            @Min(1) Integer version,
            @NotBlank @Size(max = 240) String name,
            @NotBlank @Size(max = 100) String purposeCode,
            @NotBlank @Size(max = 100) String requiredCapabilityCode) {}

    public record AddStepRequest(
            @Min(1) int sequence,
            @NotBlank @Size(max = 100) String stepCode,
            @NotBlank @Size(max = 240) String name,
            @NotBlank @Size(max = 100) String completionActionCode,
            String assignmentJson,
            String configurationJson) {}

    public record ConfigureBindingRequest(boolean enabled, String configurationJson) {}

    public record StartWorkflowRequest(
            @NotNull UUID workflowDefinitionId,
            @NotBlank @Size(max = 160) String businessKey,
            @NotBlank @Size(max = 500) String title,
            String contextJson) {}

    public record WorkflowActionRequest(
            @NotBlank @Size(max = 32) String actionType,
            @Size(max = 100) String actionCode,
            @Size(max = 100) String targetStepCode,
            String comment,
            String metadataJson) {}
}
