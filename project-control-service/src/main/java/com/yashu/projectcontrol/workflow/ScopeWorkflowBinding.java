package com.yashu.projectcontrol.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scope_workflow_bindings")
public class ScopeWorkflowBinding {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    @Column(name = "workflow_definition_id", nullable = false)
    private UUID workflowDefinitionId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "configuration_json", nullable = false, columnDefinition = "text")
    private String configurationJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ScopeWorkflowBinding() {
    }

    private ScopeWorkflowBinding(UUID id, UUID projectId, UUID scopeId, UUID workflowDefinitionId,
                                 boolean enabled, String configurationJson, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.scopeId = scopeId;
        this.workflowDefinitionId = workflowDefinitionId;
        this.enabled = enabled;
        this.configurationJson = configurationJson;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    static ScopeWorkflowBinding create(UUID projectId, UUID scopeId, UUID workflowDefinitionId,
                                       boolean enabled, String configurationJson) {
        Instant now = Instant.now();
        return new ScopeWorkflowBinding(UUID.randomUUID(), projectId, scopeId, workflowDefinitionId,
                enabled, configurationJson, now, now);
    }

    void configure(boolean enabled, String configurationJson) {
        this.enabled = enabled;
        this.configurationJson = configurationJson;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getScopeId() { return scopeId; }
    public UUID getWorkflowDefinitionId() { return workflowDefinitionId; }
    public boolean isEnabled() { return enabled; }
    public String getConfigurationJson() { return configurationJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
