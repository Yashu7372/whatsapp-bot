package com.yashu.projectcontrol.workflow;

import com.yashu.projectcontrol.scope.ScopeService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves where reusable project workflow definitions explicitly apply.
 *
 * Scope applicability is deliberately exact: a binding on a parent scope does
 * not silently flow to child scopes. A workflow is available only when an
 * enabled binding exists on that exact scope and the workflow's required
 * capability is currently enabled on that exact scope.
 */
@Service
public class WorkflowApplicabilityService {

    private final WorkflowDefinitionRepository definitionRepository;
    private final ScopeWorkflowBindingRepository bindingRepository;
    private final ScopeService scopeService;

    public WorkflowApplicabilityService(
            WorkflowDefinitionRepository definitionRepository,
            ScopeWorkflowBindingRepository bindingRepository,
            ScopeService scopeService) {
        this.definitionRepository = definitionRepository;
        this.bindingRepository = bindingRepository;
        this.scopeService = scopeService;
    }

    @Transactional(readOnly = true)
    public List<WorkflowService.DefinitionView> listAvailableDefinitions(UUID projectId, UUID scopeId) {
        scopeService.requireExistsInProject(projectId, scopeId);
        Set<String> enabledCapabilities = new HashSet<>(scopeService.listCapabilities(projectId, scopeId).stream()
                .filter(ScopeService.CapabilityView::enabled)
                .map(ScopeService.CapabilityView::capabilityCode)
                .toList());

        return bindingRepository.findByScopeIdOrderByCreatedAtAsc(scopeId).stream()
                .filter(ScopeWorkflowBinding::isEnabled)
                .filter(binding -> binding.getProjectId().equals(projectId))
                .map(binding -> definitionRepository.findByIdAndProjectId(
                        binding.getWorkflowDefinitionId(), projectId).orElse(null))
                .filter(definition -> definition != null
                        && definition.getStatus() == WorkflowDefinition.Status.ACTIVE
                        && enabledCapabilities.contains(definition.getRequiredCapabilityCode()))
                .map(WorkflowApplicabilityService::toDefinitionView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkflowService.BindingView> listProjectBindings(UUID projectId) {
        return scopeService.listByProject(projectId).stream()
                .flatMap(scope -> bindingRepository.findByScopeIdOrderByCreatedAtAsc(scope.id()).stream())
                .filter(binding -> binding.getProjectId().equals(projectId))
                .map(WorkflowApplicabilityService::toBindingView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkflowService.BindingView> listDefinitionBindings(UUID projectId, UUID definitionId) {
        requireDefinitionInProject(projectId, definitionId);
        return listProjectBindings(projectId).stream()
                .filter(binding -> binding.workflowDefinitionId().equals(definitionId))
                .toList();
    }

    private WorkflowDefinition requireDefinitionInProject(UUID projectId, UUID definitionId) {
        return definitionRepository.findByIdAndProjectId(definitionId, projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow definition not found in project: " + definitionId));
    }

    private static WorkflowService.DefinitionView toDefinitionView(WorkflowDefinition definition) {
        return new WorkflowService.DefinitionView(
                definition.getId(), definition.getProjectId(), definition.getCode(),
                definition.getVersion(), definition.getName(), definition.getPurposeCode(),
                definition.getRequiredCapabilityCode(), definition.getStatus().name(),
                definition.getCreatedAt(), definition.getUpdatedAt());
    }

    private static WorkflowService.BindingView toBindingView(ScopeWorkflowBinding binding) {
        return new WorkflowService.BindingView(
                binding.getId(), binding.getProjectId(), binding.getScopeId(),
                binding.getWorkflowDefinitionId(), binding.isEnabled(), binding.getConfigurationJson());
    }
}
