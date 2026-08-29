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
@Table(name = "workflow_actions")
public class WorkflowAction {

    public enum ActionType {
        START,
        COMMENT,
        COMPLETE_STEP,
        RETURN,
        REJECT
    }

    @Id
    private UUID id;

    @Column(name = "workflow_instance_id", nullable = false)
    private UUID workflowInstanceId;

    @Column(name = "step_instance_id")
    private UUID stepInstanceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 32)
    private ActionType actionType;

    @Column(name = "action_code", nullable = false, length = 100)
    private String actionCode;

    @Column(name = "actor_reference", length = 200)
    private String actorReference;

    @Column(name = "from_step_code", length = 100)
    private String fromStepCode;

    @Column(name = "to_step_code", length = 100)
    private String toStepCode;

    @Column(columnDefinition = "text")
    private String comment;

    @Column(name = "metadata_json", nullable = false, columnDefinition = "text")
    private String metadataJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WorkflowAction() {
    }

    private WorkflowAction(UUID id, UUID workflowInstanceId, UUID stepInstanceId,
                           ActionType actionType, String actionCode, String actorReference,
                           String fromStepCode, String toStepCode, String comment,
                           String metadataJson, Instant createdAt) {
        this.id = id;
        this.workflowInstanceId = workflowInstanceId;
        this.stepInstanceId = stepInstanceId;
        this.actionType = actionType;
        this.actionCode = actionCode;
        this.actorReference = actorReference;
        this.fromStepCode = fromStepCode;
        this.toStepCode = toStepCode;
        this.comment = comment;
        this.metadataJson = metadataJson;
        this.createdAt = createdAt;
    }

    static WorkflowAction create(UUID workflowInstanceId, UUID stepInstanceId,
                                 ActionType actionType, String actionCode,
                                 String actorReference, String fromStepCode,
                                 String toStepCode, String comment, String metadataJson) {
        return new WorkflowAction(UUID.randomUUID(), workflowInstanceId, stepInstanceId,
                actionType, actionCode, actorReference, fromStepCode, toStepCode,
                comment, metadataJson, Instant.now());
    }

    public UUID getId() { return id; }
    public UUID getWorkflowInstanceId() { return workflowInstanceId; }
    public UUID getStepInstanceId() { return stepInstanceId; }
    public ActionType getActionType() { return actionType; }
    public String getActionCode() { return actionCode; }
    public String getActorReference() { return actorReference; }
    public String getFromStepCode() { return fromStepCode; }
    public String getToStepCode() { return toStepCode; }
    public String getComment() { return comment; }
    public String getMetadataJson() { return metadataJson; }
    public Instant getCreatedAt() { return createdAt; }
}
