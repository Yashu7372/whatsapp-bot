package com.yashu.projectcontrol.scope;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scope_participants")
public class ScopeParticipant {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    @Column(name = "project_participant_id", nullable = false)
    private UUID projectParticipantId;

    @Column(length = 240)
    private String responsibility;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ScopeStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ScopeParticipant() {
    }

    private ScopeParticipant(
            UUID id,
            UUID projectId,
            UUID scopeId,
            UUID projectParticipantId,
            String responsibility,
            ScopeStatus status,
            Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.scopeId = scopeId;
        this.projectParticipantId = projectParticipantId;
        this.responsibility = responsibility;
        this.status = status;
        this.createdAt = createdAt;
    }

    static ScopeParticipant create(
            UUID projectId,
            UUID scopeId,
            UUID projectParticipantId,
            String responsibility) {
        return new ScopeParticipant(
                UUID.randomUUID(),
                projectId,
                scopeId,
                projectParticipantId,
                responsibility,
                ScopeStatus.ACTIVE,
                Instant.now());
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

    public UUID getProjectParticipantId() {
        return projectParticipantId;
    }

    public String getResponsibility() {
        return responsibility;
    }

    public ScopeStatus getStatus() {
        return status;
    }
}
