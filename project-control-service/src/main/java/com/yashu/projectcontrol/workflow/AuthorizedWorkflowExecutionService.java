package com.yashu.projectcontrol.workflow;

import com.yashu.projectcontrol.access.ProjectAccessService;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuthorizedWorkflowExecutionService {

    private final WorkflowService workflowService;
    private final ProjectAccessService accessService;

    public AuthorizedWorkflowExecutionService(
            WorkflowService workflowService,
            ProjectAccessService accessService) {
        this.workflowService = workflowService;
        this.accessService = accessService;
    }

    public WorkflowService.InstanceView act(
            UUID userId,
            UUID instanceId,
            String actionType,
            String actionCode,
            String targetStepCode,
            String comment,
            String metadataJson) {
        var instance = workflowService.getInstance(instanceId);
        String assignmentJson = instance.currentStep() == null ? "{}" : instance.currentStep().assignmentJson();
        accessService.requireWorkflowStepAssignment(
                userId, instance.projectId(), instance.scopeId(), assignmentJson);
        return workflowService.act(
                instanceId, actionType, actionCode, targetStepCode,
                userId.toString(), comment, metadataJson);
    }
}
