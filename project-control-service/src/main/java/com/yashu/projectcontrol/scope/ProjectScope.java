package com.yashu.projectcontrol.scope;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "project_scopes")
public class ProjectScope {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "parent_scope_id")
    private UUID parentScopeId;

    @Column(name = "scope_type", nullable = false, length = 80)
    private String scopeType;

    @Column(nullable = false, length = 80)
    private String code;

    @Column(nullable = false, length = 240)
    private String name;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ScopeStatus status;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "configuration_json", nullable = false)
    private String configurationJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProjectScope() {
    }

    private ProjectScope(
            UUID id,
            UUID projectId,
            UUID parentScopeId,
            String scopeType,
            String code,
            String name,
            String description,
            ScopeStatus status,
            LocalDate startDate,
            LocalDate endDate,
            String configurationJson,
            Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.parentScopeId = parentScopeId;
        this.scopeType = scopeType;
        this.code = code;
        this.name = name;
        this.description = description;
        this.status = status;
        this.startDate = startDate;
        this.endDate = endDate;
        this.configurationJson = configurationJson;
        this.createdAt = createdAt;
    }

    static ProjectScope create(
            UUID projectId,
            UUID parentScopeId,
            String scopeType,
            String code,
            String name,
            String description,
            LocalDate startDate,
            LocalDate endDate,
            String configurationJson) {
        return new ProjectScope(
                UUID.randomUUID(),
                projectId,
                parentScopeId,
                scopeType,
                code,
                name,
                description,
                ScopeStatus.ACTIVE,
                startDate,
                endDate,
                configurationJson,
                Instant.now());
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getParentScopeId() {
        return parentScopeId;
    }

    public String getScopeType() {
        return scopeType;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public ScopeStatus getStatus() {
        return status;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public String getConfigurationJson() {
        return configurationJson;
    }
}
