package com.yashu.projectcontrol.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflow_instances")
public class WorkflowInstance {

    public enum Status {
        RUNNING,
        COMPLETED,
        REJECTED,
        CANCELLED
    }

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    @Column(name = "workflow_definition_id", nullable = false)
    private UUID workflowDefinitionId;

    @Column(name = "business_key", nullable = false, length = 160)
    private String businessKey;

    @Column(nullable = false, length = 500)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;

    @Column(name = "current_step_instance_id")
    private UUID currentStepInstanceId;

    @Column(name = "current_step_sequence")
    private Integer currentStepSequence;

    @Column(name = "current_step_code", length = 100)
    private String currentStepCode;

    @Column(name = "initiated_by_reference", length = 200)
    private String initiatedByReference;

    @Column(name = "initiated_at", nullable = false, updatable = false)
    private Instant initiatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "context_json", nullable = false, columnDefinition = "text")
    private String contextJson;

    protected WorkflowInstance() {
    }

    private WorkflowInstance(UUID id, UUID projectId, UUID scopeId, UUID workflowDefinitionId,
                             String businessKey, String title, Status status,
                             UUID currentStepInstanceId, Integer currentStepSequence, String currentStepCode,
                             String initiatedByReference, Instant initiatedAt, Instant completedAt,
                             String contextJson) {
        this.id = id;
        this.projectId = projectId;
        this.scopeId = scopeId;
        this.workflowDefinitionId = workflowDefinitionId;
        this.businessKey = businessKey;
        this.title = title;
        this.status = status;
        this.currentStepInstanceId = currentStepInstanceId;
        this.currentStepSequence = currentStepSequence;
        this.currentStepCode = currentStepCode;
        this.initiatedByReference = initiatedByReference;
        this.initiatedAt = initiatedAt;
        this.completedAt = completedAt;
        this.contextJson = contextJson;
    }

    static WorkflowInstance create(UUID projectId, UUID scopeId, UUID workflowDefinitionId,
                                   String businessKey, String title, String initiatedByReference,
                                   String contextJson) {
        return new WorkflowInstance(UUID.randomUUID(), projectId, scopeId, workflowDefinitionId,
                businessKey, title, Status.RUNNING, null, null, null,
                initiatedByReference, Instant.now(), null, contextJson);
    }

    void moveTo(UUID stepInstanceId, int stepSequence, String stepCode) {
        requireRunning();
        this.currentStepInstanceId = stepInstanceId;
        this.currentStepSequence = stepSequence;
        this.currentStepCode = stepCode;
    }

    void complete() {
        requireRunning();
        this.status = Status.COMPLETED;
        this.currentStepInstanceId = null;
        this.currentStepSequence = null;
        this.currentStepCode = null;
        this.completedAt = Instant.now();
    }

    void reject() {
        requireRunning();
        this.status = Status.REJECTED;
        this.currentStepInstanceId = null;
        this.currentStepSequence = null;
        this.currentStepCode = null;
        this.completedAt = Instant.now();
    }

    void requireRunning() {
        if (status != Status.RUNNING) {
            throw new IllegalStateException("Workflow instance is not running");
        }
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getScopeId() { return scopeId; }
    public UUID getWorkflowDefinitionId() { return workflowDefinitionId; }
    public String getBusinessKey() { return businessKey; }
    public String getTitle() { return title; }
    public Status getStatus() { return status; }
    public UUID getCurrentStepInstanceId() { return currentStepInstanceId; }
    public Integer getCurrentStepSequence() { return currentStepSequence; }
    public String getCurrentStepCode() { return currentStepCode; }
    public String getInitiatedByReference() { return initiatedByReference; }
    public Instant getInitiatedAt() { return initiatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getContextJson() { return contextJson; }
}
