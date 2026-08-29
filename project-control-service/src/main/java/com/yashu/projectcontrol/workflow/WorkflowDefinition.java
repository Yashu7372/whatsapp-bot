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
@Table(name = "workflow_definitions")
public class WorkflowDefinition {

    public enum Status {
        DRAFT,
        ACTIVE,
        RETIRED
    }

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false, length = 100)
    private String code;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 240)
    private String name;

    @Column(name = "purpose_code", nullable = false, length = 100)
    private String purposeCode;

    @Column(name = "required_capability_code", nullable = false, length = 100)
    private String requiredCapabilityCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowDefinition() {
    }

    private WorkflowDefinition(UUID id, UUID projectId, String code, int version, String name,
                               String purposeCode, String requiredCapabilityCode, Status status,
                               Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.code = code;
        this.version = version;
        this.name = name;
        this.purposeCode = purposeCode;
        this.requiredCapabilityCode = requiredCapabilityCode;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    static WorkflowDefinition create(UUID projectId, String code, int version, String name,
                                     String purposeCode, String requiredCapabilityCode) {
        Instant now = Instant.now();
        return new WorkflowDefinition(UUID.randomUUID(), projectId, code, version, name,
                purposeCode, requiredCapabilityCode, Status.DRAFT, now, now);
    }

    void activate() {
        if (status != Status.DRAFT) {
            throw new IllegalStateException("Only draft workflow definitions can be activated");
        }
        status = Status.ACTIVE;
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public String getCode() { return code; }
    public int getVersion() { return version; }
    public String getName() { return name; }
    public String getPurposeCode() { return purposeCode; }
    public String getRequiredCapabilityCode() { return requiredCapabilityCode; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
