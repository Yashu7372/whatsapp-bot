package com.yashu.projectcontrol.scope;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scope_capabilities")
public class ScopeCapability {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    @Column(name = "capability_code", nullable = false, length = 100)
    private String capabilityCode;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "configuration_json", nullable = false)
    private String configurationJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ScopeCapability() {
    }

    private ScopeCapability(
            UUID id,
            UUID projectId,
            UUID scopeId,
            String capabilityCode,
            boolean enabled,
            String configurationJson,
            Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.scopeId = scopeId;
        this.capabilityCode = capabilityCode;
        this.enabled = enabled;
        this.configurationJson = configurationJson;
        this.createdAt = createdAt;
    }

    static ScopeCapability create(
            UUID projectId,
            UUID scopeId,
            String capabilityCode,
            boolean enabled,
            String configurationJson) {
        return new ScopeCapability(
                UUID.randomUUID(),
                projectId,
                scopeId,
                capabilityCode,
                enabled,
                configurationJson,
                Instant.now());
    }

    void configure(boolean enabled, String configurationJson) {
        this.enabled = enabled;
        this.configurationJson = configurationJson;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getScopeId() {
        return scopeId;
    }

    public String getCapabilityCode() {
        return capabilityCode;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getConfigurationJson() {
        return configurationJson;
    }
}
