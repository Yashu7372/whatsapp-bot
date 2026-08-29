package com.yashu.projectcontrol.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflow_step_definitions")
public class WorkflowStepDefinition {

    @Id
    private UUID id;

    @Column(name = "workflow_definition_id", nullable = false)
    private UUID workflowDefinitionId;

    @Column(name = "step_sequence", nullable = false)
    private int stepSequence;

    @Column(name = "step_code", nullable = false, length = 100)
    private String stepCode;

    @Column(nullable = false, length = 240)
    private String name;

    @Column(name = "completion_action_code", nullable = false, length = 100)
    private String completionActionCode;

    @Column(name = "assignment_json", nullable = false, columnDefinition = "text")
    private String assignmentJson;

    @Column(name = "configuration_json", nullable = false, columnDefinition = "text")
    private String configurationJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WorkflowStepDefinition() {
    }

    private WorkflowStepDefinition(UUID id, UUID workflowDefinitionId, int stepSequence,
                                   String stepCode, String name, String completionActionCode,
                                   String assignmentJson, String configurationJson, Instant createdAt) {
        this.id = id;
        this.workflowDefinitionId = workflowDefinitionId;
        this.stepSequence = stepSequence;
        this.stepCode = stepCode;
        this.name = name;
        this.completionActionCode = completionActionCode;
        this.assignmentJson = assignmentJson;
        this.configurationJson = configurationJson;
        this.createdAt = createdAt;
    }

    static WorkflowStepDefinition create(UUID workflowDefinitionId, int stepSequence,
                                         String stepCode, String name, String completionActionCode,
                                         String assignmentJson, String configurationJson) {
        return new WorkflowStepDefinition(UUID.randomUUID(), workflowDefinitionId, stepSequence,
                stepCode, name, completionActionCode, assignmentJson, configurationJson, Instant.now());
    }

    public UUID getId() { return id; }
    public UUID getWorkflowDefinitionId() { return workflowDefinitionId; }
    public int getStepSequence() { return stepSequence; }
    public String getStepCode() { return stepCode; }
    public String getName() { return name; }
    public String getCompletionActionCode() { return completionActionCode; }
    public String getAssignmentJson() { return assignmentJson; }
    public String getConfigurationJson() { return configurationJson; }
    public Instant getCreatedAt() { return createdAt; }
}
