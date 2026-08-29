package com.yashu.projectcontrol.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class WorkflowController {

    private final WorkflowService service;

    public WorkflowController(WorkflowService service) {
        this.service = service;
    }

    @PostMapping("/projects/{projectId}/workflow-definitions")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowService.DefinitionView createDefinition(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateDefinitionRequest request) {
        return service.createDefinition(
                projectId,
                request.code(),
                request.version(),
                request.name(),
                request.purposeCode(),
                request.requiredCapabilityCode());
    }

    @GetMapping("/projects/{projectId}/workflow-definitions")
    public List<WorkflowService.DefinitionView> listDefinitions(@PathVariable UUID projectId) {
        return service.listDefinitions(projectId);
    }

    @PostMapping("/workflow-definitions/{definitionId}/steps")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowService.StepDefinitionView addStep(
            @PathVariable UUID definitionId,
            @Valid @RequestBody AddStepRequest request) {
        return service.addStep(
                definitionId,
                request.sequence(),
                request.stepCode(),
                request.name(),
                request.completionActionCode(),
                request.assignmentJson(),
                request.configurationJson());
    }

    @GetMapping("/workflow-definitions/{definitionId}/steps")
    public List<WorkflowService.StepDefinitionView> listSteps(@PathVariable UUID definitionId) {
        return service.listDefinitionSteps(definitionId);
    }

    @PostMapping("/workflow-definitions/{definitionId}/activate")
    public WorkflowService.DefinitionView activate(@PathVariable UUID definitionId) {
        return service.activateDefinition(definitionId);
    }

    @PutMapping("/projects/{projectId}/scopes/{scopeId}/workflow-bindings/{definitionId}")
    public WorkflowService.BindingView bindToScope(
            @PathVariable UUID projectId,
            @PathVariable UUID scopeId,
            @PathVariable UUID definitionId,
            @Valid @RequestBody ConfigureBindingRequest request) {
        return service.setScopeBinding(
                projectId, scopeId, definitionId, request.enabled(), request.configurationJson());
    }

    @GetMapping("/projects/{projectId}/scopes/{scopeId}/workflow-bindings")
    public List<WorkflowService.BindingView> listBindings(
            @PathVariable UUID projectId,
            @PathVariable UUID scopeId) {
        return service.listScopeBindings(projectId, scopeId);
    }

    @PostMapping("/projects/{projectId}/scopes/{scopeId}/workflow-instances")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowService.InstanceView start(
            @PathVariable UUID projectId,
            @PathVariable UUID scopeId,
            @Valid @RequestBody StartWorkflowRequest request) {
        return service.start(
                projectId,
                scopeId,
                request.workflowDefinitionId(),
                request.businessKey(),
                request.title(),
                request.initiatedByReference(),
                request.contextJson());
    }

    @PostMapping("/workflow-instances/{instanceId}/actions")
    public WorkflowService.InstanceView act(
            @PathVariable UUID instanceId,
            @Valid @RequestBody WorkflowActionRequest request) {
        return service.act(
                instanceId,
                request.actionType(),
                request.actionCode(),
                request.targetStepCode(),
                request.actorReference(),
                request.comment(),
                request.metadataJson());
    }

    @GetMapping("/workflow-instances/{instanceId}")
    public WorkflowService.InstanceView get(@PathVariable UUID instanceId) {
        return service.getInstance(instanceId);
    }

    @GetMapping("/workflow-instances/{instanceId}/history")
    public WorkflowService.HistoryView history(@PathVariable UUID instanceId) {
        return service.history(instanceId);
    }

    public record CreateDefinitionRequest(
            @NotBlank @Size(max = 100) String code,
            @Min(1) Integer version,
            @NotBlank @Size(max = 240) String name,
            @NotBlank @Size(max = 100) String purposeCode,
            @NotBlank @Size(max = 100) String requiredCapabilityCode) {
    }

    public record AddStepRequest(
            @Min(1) int sequence,
            @NotBlank @Size(max = 100) String stepCode,
            @NotBlank @Size(max = 240) String name,
            @NotBlank @Size(max = 100) String completionActionCode,
            String assignmentJson,
            String configurationJson) {
    }

    public record ConfigureBindingRequest(boolean enabled, String configurationJson) {
    }

    public record StartWorkflowRequest(
            @NotNull UUID workflowDefinitionId,
            @NotBlank @Size(max = 160) String businessKey,
            @NotBlank @Size(max = 500) String title,
            @Size(max = 200) String initiatedByReference,
            String contextJson) {
    }

    public record WorkflowActionRequest(
            @NotBlank @Size(max = 32) String actionType,
            @Size(max = 100) String actionCode,
            @Size(max = 100) String targetStepCode,
            @Size(max = 200) String actorReference,
            String comment,
            String metadataJson) {
    }
}
