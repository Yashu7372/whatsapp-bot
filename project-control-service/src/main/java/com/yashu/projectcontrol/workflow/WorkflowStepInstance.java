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
@Table(name = "workflow_step_instances")
public class WorkflowStepInstance {

    public enum Status {
        ACTIVE,
        COMPLETED,
        RETURNED,
        REJECTED
    }

    @Id
    private UUID id;

    @Column(name = "workflow_instance_id", nullable = false)
    private UUID workflowInstanceId;

    @Column(name = "step_definition_id", nullable = false)
    private UUID stepDefinitionId;

    @Column(name = "step_sequence", nullable = false)
    private int stepSequence;

    @Column(name = "step_code", nullable = false, length = 100)
    private String stepCode;

    @Column(name = "step_name", nullable = false, length = 240)
    private String stepName;

    @Column(name = "visit_number", nullable = false)
    private int visitNumber;

    @Column(name = "assignment_json", nullable = false, columnDefinition = "text")
    private String assignmentJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;

    @Column(name = "activated_at", nullable = false, updatable = false)
    private Instant activatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected WorkflowStepInstance() {
    }

    private WorkflowStepInstance(UUID id, UUID workflowInstanceId, UUID stepDefinitionId,
                                 int stepSequence, String stepCode, String stepName, int visitNumber,
                                 String assignmentJson, Status status, Instant activatedAt,
                                 Instant completedAt) {
        this.id = id;
        this.workflowInstanceId = workflowInstanceId;
        this.stepDefinitionId = stepDefinitionId;
        this.stepSequence = stepSequence;
        this.stepCode = stepCode;
        this.stepName = stepName;
        this.visitNumber = visitNumber;
        this.assignmentJson = assignmentJson;
        this.status = status;
        this.activatedAt = activatedAt;
        this.completedAt = completedAt;
    }

    static WorkflowStepInstance activate(UUID workflowInstanceId, WorkflowStepDefinition definition,
                                         int visitNumber) {
        return new WorkflowStepInstance(UUID.randomUUID(), workflowInstanceId, definition.getId(),
                definition.getStepSequence(), definition.getStepCode(), definition.getName(),
                visitNumber, definition.getAssignmentJson(), Status.ACTIVE, Instant.now(), null);
    }

    void complete() {
        requireActive();
        status = Status.COMPLETED;
        completedAt = Instant.now();
    }

    void returned() {
        requireActive();
        status = Status.RETURNED;
        completedAt = Instant.now();
    }

    void reject() {
        requireActive();
        status = Status.REJECTED;
        completedAt = Instant.now();
    }

    private void requireActive() {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("Workflow step instance is not active");
        }
    }

    public UUID getId() { return id; }
    public UUID getWorkflowInstanceId() { return workflowInstanceId; }
    public UUID getStepDefinitionId() { return stepDefinitionId; }
    public int getStepSequence() { return stepSequence; }
    public String getStepCode() { return stepCode; }
    public String getStepName() { return stepName; }
    public int getVisitNumber() { return visitNumber; }
    public String getAssignmentJson() { return assignmentJson; }
    public Status getStatus() { return status; }
    public Instant getActivatedAt() { return activatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
