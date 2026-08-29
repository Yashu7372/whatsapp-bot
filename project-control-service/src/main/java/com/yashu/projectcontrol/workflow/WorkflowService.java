package com.yashu.projectcontrol.workflow;

import com.yashu.projectcontrol.project.ProjectService;
import com.yashu.projectcontrol.scope.ScopeService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class WorkflowService {

    private final WorkflowDefinitionRepository definitionRepository;
    private final WorkflowStepDefinitionRepository stepDefinitionRepository;
    private final ScopeWorkflowBindingRepository bindingRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowStepInstanceRepository stepInstanceRepository;
    private final WorkflowActionRepository actionRepository;
    private final ProjectService projectService;
    private final ScopeService scopeService;

    public WorkflowService(
            WorkflowDefinitionRepository definitionRepository,
            WorkflowStepDefinitionRepository stepDefinitionRepository,
            ScopeWorkflowBindingRepository bindingRepository,
            WorkflowInstanceRepository instanceRepository,
            WorkflowStepInstanceRepository stepInstanceRepository,
            WorkflowActionRepository actionRepository,
            ProjectService projectService,
            ScopeService scopeService) {
        this.definitionRepository = definitionRepository;
        this.stepDefinitionRepository = stepDefinitionRepository;
        this.bindingRepository = bindingRepository;
        this.instanceRepository = instanceRepository;
        this.stepInstanceRepository = stepInstanceRepository;
        this.actionRepository = actionRepository;
        this.projectService = projectService;
        this.scopeService = scopeService;
    }

    @Transactional
    public DefinitionView createDefinition(UUID projectId, String code, Integer version, String name,
                                           String purposeCode, String requiredCapabilityCode) {
        projectService.requireExists(projectId);
        String normalizedCode = normalizeCode(code, "code");
        int normalizedVersion = version == null ? 1 : version;
        if (normalizedVersion < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "version must be at least 1");
        }
        if (definitionRepository.existsByProjectIdAndCodeIgnoreCaseAndVersion(
                projectId, normalizedCode, normalizedVersion)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Workflow definition version already exists: " + normalizedCode + " v" + normalizedVersion);
        }

        WorkflowDefinition definition = definitionRepository.save(WorkflowDefinition.create(
                projectId,
                normalizedCode,
                normalizedVersion,
                requireText(name, "name"),
                normalizeCode(purposeCode, "purposeCode"),
                normalizeCode(requiredCapabilityCode, "requiredCapabilityCode")));
        return toDefinitionView(definition);
    }

    @Transactional
    public StepDefinitionView addStep(UUID definitionId, int sequence, String stepCode, String name,
                                      String completionActionCode, String assignmentJson,
                                      String configurationJson) {
        WorkflowDefinition definition = requireDefinition(definitionId);
        if (definition.getStatus() != WorkflowDefinition.Status.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Workflow steps can only be changed while the definition is DRAFT");
        }
        if (sequence < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sequence must be at least 1");
        }
        String normalizedStepCode = normalizeCode(stepCode, "stepCode");
        if (stepDefinitionRepository.existsByWorkflowDefinitionIdAndStepSequence(definitionId, sequence)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Workflow step sequence already exists: " + sequence);
        }
        if (stepDefinitionRepository.existsByWorkflowDefinitionIdAndStepCodeIgnoreCase(
                definitionId, normalizedStepCode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Workflow step code already exists: " + normalizedStepCode);
        }

        WorkflowStepDefinition step = stepDefinitionRepository.save(WorkflowStepDefinition.create(
                definitionId,
                sequence,
                normalizedStepCode,
                requireText(name, "name"),
                normalizeCode(completionActionCode, "completionActionCode"),
                normalizeJson(assignmentJson),
                normalizeJson(configurationJson)));
        return toStepDefinitionView(step);
    }

    @Transactional
    public DefinitionView activateDefinition(UUID definitionId) {
        WorkflowDefinition definition = requireDefinition(definitionId);
        if (definition.getStatus() != WorkflowDefinition.Status.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only DRAFT workflow definitions can be activated");
        }
        List<WorkflowStepDefinition> steps = stepDefinitionRepository
                .findByWorkflowDefinitionIdOrderByStepSequenceAsc(definitionId);
        if (steps.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Workflow definition must contain at least one step");
        }
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).getStepSequence() != i + 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Workflow step sequences must be contiguous starting at 1");
            }
        }
        definition.activate();
        return toDefinitionView(definitionRepository.save(definition));
    }

    @Transactional(readOnly = true)
    public List<DefinitionView> listDefinitions(UUID projectId) {
        projectService.requireExists(projectId);
        return definitionRepository.findByProjectIdOrderByCodeAscVersionAsc(projectId).stream()
                .map(WorkflowService::toDefinitionView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StepDefinitionView> listDefinitionSteps(UUID definitionId) {
        requireDefinition(definitionId);
        return stepDefinitionRepository.findByWorkflowDefinitionIdOrderByStepSequenceAsc(definitionId).stream()
                .map(WorkflowService::toStepDefinitionView)
                .toList();
    }

    @Transactional
    public BindingView setScopeBinding(UUID projectId, UUID scopeId, UUID definitionId,
                                       boolean enabled, String configurationJson) {
        scopeService.requireExistsInProject(projectId, scopeId);
        WorkflowDefinition definition = requireDefinitionInProject(projectId, definitionId);
        if (enabled) {
            if (definition.getStatus() != WorkflowDefinition.Status.ACTIVE) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Only ACTIVE workflow definitions can be enabled on a scope");
            }
            scopeService.requireEnabledCapability(
                    projectId, scopeId, definition.getRequiredCapabilityCode());
        }

        String normalizedConfiguration = normalizeJson(configurationJson);
        ScopeWorkflowBinding binding = bindingRepository.findByScopeIdAndWorkflowDefinitionId(scopeId, definitionId)
                .map(existing -> {
                    existing.configure(enabled, normalizedConfiguration);
                    return existing;
                })
                .orElseGet(() -> ScopeWorkflowBinding.create(
                        projectId, scopeId, definitionId, enabled, normalizedConfiguration));
        return toBindingView(bindingRepository.save(binding));
    }

    @Transactional(readOnly = true)
    public List<BindingView> listScopeBindings(UUID projectId, UUID scopeId) {
        scopeService.requireExistsInProject(projectId, scopeId);
        return bindingRepository.findByScopeIdOrderByCreatedAtAsc(scopeId).stream()
                .map(WorkflowService::toBindingView)
                .toList();
    }

    @Transactional
    public InstanceView start(UUID projectId, UUID scopeId, UUID definitionId,
                              String businessKey, String title, String initiatedByReference,
                              String contextJson) {
        scopeService.requireExistsInProject(projectId, scopeId);
        WorkflowDefinition definition = requireDefinitionInProject(projectId, definitionId);
        if (definition.getStatus() != WorkflowDefinition.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Workflow definition is not ACTIVE");
        }
        bindingRepository.findByScopeIdAndWorkflowDefinitionIdAndEnabledTrue(scopeId, definitionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Workflow definition is not enabled on this scope"));
        scopeService.requireEnabledCapability(projectId, scopeId, definition.getRequiredCapabilityCode());

        String normalizedBusinessKey = normalizeCode(businessKey, "businessKey");
        if (instanceRepository.existsByProjectIdAndWorkflowDefinitionIdAndBusinessKeyIgnoreCase(
                projectId, definitionId, normalizedBusinessKey)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Workflow instance business key already exists: " + normalizedBusinessKey);
        }

        WorkflowStepDefinition firstStep = stepDefinitionRepository
                .findByWorkflowDefinitionIdAndStepSequence(definitionId, 1)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Active workflow definition has no first step"));

        WorkflowInstance instance = instanceRepository.save(WorkflowInstance.create(
                projectId,
                scopeId,
                definitionId,
                normalizedBusinessKey,
                requireText(title, "title"),
                normalizeOptional(initiatedByReference),
                normalizeJson(contextJson)));

        WorkflowStepInstance firstVisit = stepInstanceRepository.save(
                WorkflowStepInstance.activate(instance.getId(), firstStep, 1));
        instance.moveTo(firstVisit.getId(), firstVisit.getStepSequence(), firstVisit.getStepCode());
        instanceRepository.save(instance);

        actionRepository.save(WorkflowAction.create(
                instance.getId(),
                firstVisit.getId(),
                WorkflowAction.ActionType.START,
                "START",
                normalizeOptional(initiatedByReference),
                null,
                firstVisit.getStepCode(),
                null,
                "{}"));

        return toInstanceView(instance, definition, firstVisit);
    }

    @Transactional
    public InstanceView act(UUID instanceId, String actionType, String actionCode,
                            String targetStepCode, String actorReference, String comment,
                            String metadataJson) {
        WorkflowInstance instance = instanceRepository.lockById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow instance not found: " + instanceId));
        if (instance.getStatus() != WorkflowInstance.Status.RUNNING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Workflow instance is not running");
        }
        UUID currentStepInstanceId = instance.getCurrentStepInstanceId();
        if (currentStepInstanceId == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Running workflow has no current step");
        }
        WorkflowStepInstance currentStep = stepInstanceRepository
                .findByIdAndWorkflowInstanceId(currentStepInstanceId, instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Workflow current step is missing"));
        WorkflowDefinition definition = requireDefinition(instance.getWorkflowDefinitionId());
        WorkflowStepDefinition currentDefinition = stepDefinitionRepository.findById(currentStep.getStepDefinitionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Workflow step definition is missing"));

        WorkflowAction.ActionType type;
        try {
            type = WorkflowAction.ActionType.valueOf(normalizeCode(actionType, "actionType"));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported workflow action type: " + actionType);
        }
        if (type == WorkflowAction.ActionType.START) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "START is created by the workflow service and cannot be submitted as an action");
        }

        String normalizedActor = normalizeOptional(actorReference);
        String normalizedComment = normalizeOptional(comment);
        String normalizedMetadata = normalizeJson(metadataJson);
        String fromStep = currentStep.getStepCode();
        String toStep = fromStep;
        String persistedActionCode;

        switch (type) {
            case COMMENT -> {
                if (normalizedComment == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "comment is required for COMMENT actions");
                }
                persistedActionCode = actionCode == null || actionCode.isBlank()
                        ? "COMMENT" : normalizeCode(actionCode, "actionCode");
            }
            case COMPLETE_STEP -> {
                persistedActionCode = normalizeCode(actionCode, "actionCode");
                if (!currentDefinition.getCompletionActionCode().equals(persistedActionCode)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Current step expects completion action '"
                                    + currentDefinition.getCompletionActionCode() + "'");
                }
                currentStep.complete();
                stepInstanceRepository.save(currentStep);

                WorkflowStepDefinition nextDefinition = stepDefinitionRepository
                        .findByWorkflowDefinitionIdAndStepSequence(
                                definition.getId(), currentStep.getStepSequence() + 1)
                        .orElse(null);
                if (nextDefinition == null) {
                    instance.complete();
                    toStep = null;
                } else {
                    WorkflowStepInstance nextStep = activateVisit(instance.getId(), nextDefinition);
                    instance.moveTo(nextStep.getId(), nextStep.getStepSequence(), nextStep.getStepCode());
                    toStep = nextStep.getStepCode();
                }
                instanceRepository.save(instance);
            }
            case RETURN -> {
                if (normalizedComment == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "comment is required for RETURN actions");
                }
                String normalizedTarget = normalizeCode(targetStepCode, "targetStepCode");
                WorkflowStepDefinition targetDefinition = stepDefinitionRepository
                        .findByWorkflowDefinitionIdAndStepCodeIgnoreCase(definition.getId(), normalizedTarget)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Return target step does not exist: " + normalizedTarget));
                if (targetDefinition.getStepSequence() >= currentStep.getStepSequence()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "RETURN target must be an earlier workflow step");
                }
                currentStep.returned();
                stepInstanceRepository.save(currentStep);
                WorkflowStepInstance targetVisit = activateVisit(instance.getId(), targetDefinition);
                instance.moveTo(targetVisit.getId(), targetVisit.getStepSequence(), targetVisit.getStepCode());
                instanceRepository.save(instance);
                persistedActionCode = actionCode == null || actionCode.isBlank()
                        ? "RETURN" : normalizeCode(actionCode, "actionCode");
                toStep = targetVisit.getStepCode();
            }
            case REJECT -> {
                if (normalizedComment == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "comment is required for REJECT actions");
                }
                currentStep.reject();
                stepInstanceRepository.save(currentStep);
                instance.reject();
                instanceRepository.save(instance);
                persistedActionCode = actionCode == null || actionCode.isBlank()
                        ? "REJECT" : normalizeCode(actionCode, "actionCode");
                toStep = null;
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported workflow action type: " + type);
        }

        actionRepository.save(WorkflowAction.create(
                instance.getId(),
                currentStep.getId(),
                type,
                persistedActionCode,
                normalizedActor,
                fromStep,
                toStep,
                normalizedComment,
                normalizedMetadata));

        WorkflowStepInstance activeStep = instance.getCurrentStepInstanceId() == null
                ? null
                : stepInstanceRepository.findByIdAndWorkflowInstanceId(
                        instance.getCurrentStepInstanceId(), instance.getId()).orElse(null);
        return toInstanceView(instance, definition, activeStep);
    }

    @Transactional(readOnly = true)
    public InstanceView getInstance(UUID instanceId) {
        WorkflowInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow instance not found: " + instanceId));
        WorkflowDefinition definition = requireDefinition(instance.getWorkflowDefinitionId());
        WorkflowStepInstance current = instance.getCurrentStepInstanceId() == null
                ? null
                : stepInstanceRepository.findByIdAndWorkflowInstanceId(
                        instance.getCurrentStepInstanceId(), instance.getId()).orElse(null);
        return toInstanceView(instance, definition, current);
    }

    @Transactional(readOnly = true)
    public HistoryView history(UUID instanceId) {
        getInstance(instanceId);
        List<StepInstanceView> steps = stepInstanceRepository
                .findByWorkflowInstanceIdOrderByActivatedAtAsc(instanceId).stream()
                .map(WorkflowService::toStepInstanceView)
                .toList();
        List<ActionView> actions = actionRepository
                .findByWorkflowInstanceIdOrderByCreatedAtAsc(instanceId).stream()
                .map(WorkflowService::toActionView)
                .toList();
        return new HistoryView(instanceId, steps, actions);
    }

    private WorkflowStepInstance activateVisit(UUID instanceId, WorkflowStepDefinition definition) {
        int visit = stepInstanceRepository
                .findTopByWorkflowInstanceIdAndStepSequenceOrderByVisitNumberDesc(
                        instanceId, definition.getStepSequence())
                .map(existing -> existing.getVisitNumber() + 1)
                .orElse(1);
        return stepInstanceRepository.save(WorkflowStepInstance.activate(instanceId, definition, visit));
    }

    private WorkflowDefinition requireDefinition(UUID definitionId) {
        return definitionRepository.findById(definitionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow definition not found: " + definitionId));
    }

    private WorkflowDefinition requireDefinitionInProject(UUID projectId, UUID definitionId) {
        return definitionRepository.findByIdAndProjectId(definitionId, projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow definition not found in project: " + definitionId));
    }

    private static String normalizeCode(String value, String field) {
        return requireText(value, field).toUpperCase(Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeJson(String value) {
        return value == null || value.isBlank() ? "{}" : value.trim();
    }

    private static DefinitionView toDefinitionView(WorkflowDefinition definition) {
        return new DefinitionView(
                definition.getId(), definition.getProjectId(), definition.getCode(),
                definition.getVersion(), definition.getName(), definition.getPurposeCode(),
                definition.getRequiredCapabilityCode(), definition.getStatus().name(),
                definition.getCreatedAt(), definition.getUpdatedAt());
    }

    private static StepDefinitionView toStepDefinitionView(WorkflowStepDefinition step) {
        return new StepDefinitionView(
                step.getId(), step.getWorkflowDefinitionId(), step.getStepSequence(),
                step.getStepCode(), step.getName(), step.getCompletionActionCode(),
                step.getAssignmentJson(), step.getConfigurationJson());
    }

    private static BindingView toBindingView(ScopeWorkflowBinding binding) {
        return new BindingView(
                binding.getId(), binding.getProjectId(), binding.getScopeId(),
                binding.getWorkflowDefinitionId(), binding.isEnabled(), binding.getConfigurationJson());
    }

    private static InstanceView toInstanceView(WorkflowInstance instance, WorkflowDefinition definition,
                                               WorkflowStepInstance currentStep) {
        return new InstanceView(
                instance.getId(), instance.getProjectId(), instance.getScopeId(),
                instance.getWorkflowDefinitionId(), definition.getCode(), definition.getVersion(),
                definition.getPurposeCode(), definition.getRequiredCapabilityCode(),
                instance.getBusinessKey(), instance.getTitle(), instance.getStatus().name(),
                currentStep == null ? null : toStepInstanceView(currentStep),
                instance.getInitiatedByReference(), instance.getInitiatedAt(),
                instance.getCompletedAt(), instance.getContextJson());
    }

    private static StepInstanceView toStepInstanceView(WorkflowStepInstance step) {
        return new StepInstanceView(
                step.getId(), step.getWorkflowInstanceId(), step.getStepDefinitionId(),
                step.getStepSequence(), step.getStepCode(), step.getStepName(),
                step.getVisitNumber(), step.getAssignmentJson(), step.getStatus().name(),
                step.getActivatedAt(), step.getCompletedAt());
    }

    private static ActionView toActionView(WorkflowAction action) {
        return new ActionView(
                action.getId(), action.getWorkflowInstanceId(), action.getStepInstanceId(),
                action.getActionType().name(), action.getActionCode(), action.getActorReference(),
                action.getFromStepCode(), action.getToStepCode(), action.getComment(),
                action.getMetadataJson(), action.getCreatedAt());
    }

    public record DefinitionView(
            UUID id, UUID projectId, String code, int version, String name,
            String purposeCode, String requiredCapabilityCode, String status,
            Instant createdAt, Instant updatedAt) {
    }

    public record StepDefinitionView(
            UUID id, UUID workflowDefinitionId, int sequence, String stepCode, String name,
            String completionActionCode, String assignmentJson, String configurationJson) {
    }

    public record BindingView(
            UUID id, UUID projectId, UUID scopeId, UUID workflowDefinitionId,
            boolean enabled, String configurationJson) {
    }

    public record InstanceView(
            UUID id, UUID projectId, UUID scopeId, UUID workflowDefinitionId,
            String workflowCode, int workflowVersion, String purposeCode,
            String requiredCapabilityCode, String businessKey, String title,
            String status, StepInstanceView currentStep, String initiatedByReference,
            Instant initiatedAt, Instant completedAt, String contextJson) {
    }

    public record StepInstanceView(
            UUID id, UUID workflowInstanceId, UUID stepDefinitionId, int sequence,
            String stepCode, String stepName, int visitNumber, String assignmentJson,
            String status, Instant activatedAt, Instant completedAt) {
    }

    public record ActionView(
            UUID id, UUID workflowInstanceId, UUID stepInstanceId, String actionType,
            String actionCode, String actorReference, String fromStepCode, String toStepCode,
            String comment, String metadataJson, Instant createdAt) {
    }

    public record HistoryView(UUID workflowInstanceId,
                              List<StepInstanceView> steps,
                              List<ActionView> actions) {
    }
}
