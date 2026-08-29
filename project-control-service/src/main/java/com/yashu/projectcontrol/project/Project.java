package com.yashu.projectcontrol.project;

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
@Table(name = "projects")
public class Project {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 240)
    private String name;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProjectStatus status;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Project() {
    }

    private Project(
            UUID id,
            UUID workspaceId,
            String code,
            String name,
            String description,
            ProjectStatus status,
            LocalDate startDate,
            LocalDate endDate,
            String currency,
            String timeZone,
            Instant createdAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.code = code;
        this.name = name;
        this.description = description;
        this.status = status;
        this.startDate = startDate;
        this.endDate = endDate;
        this.currency = currency;
        this.timeZone = timeZone;
        this.createdAt = createdAt;
    }

    static Project create(
            UUID workspaceId,
            String code,
            String name,
            String description,
            LocalDate startDate,
            LocalDate endDate,
            String currency,
            String timeZone) {
        return new Project(
                UUID.randomUUID(),
                workspaceId,
                code,
                name,
                description,
                ProjectStatus.DRAFT,
                startDate,
                endDate,
                currency,
                timeZone,
                Instant.now());
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
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

    public ProjectStatus getStatus() {
        return status;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public String getCurrency() {
        return currency;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
