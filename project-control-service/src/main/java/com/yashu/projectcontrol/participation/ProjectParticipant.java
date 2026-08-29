package com.yashu.projectcontrol.participation;

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
@Table(name = "project_participants")
public class ProjectParticipant {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "party_role", nullable = false, length = 80)
    private String partyRole;

    @Column(name = "parent_participant_id")
    private UUID parentParticipantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ParticipationStatus status;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProjectParticipant() {
    }

    private ProjectParticipant(
            UUID id,
            UUID projectId,
            UUID organizationId,
            String partyRole,
            UUID parentParticipantId,
            ParticipationStatus status,
            LocalDate validFrom,
            LocalDate validTo,
            Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.organizationId = organizationId;
        this.partyRole = partyRole;
        this.parentParticipantId = parentParticipantId;
        this.status = status;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.createdAt = createdAt;
    }

    static ProjectParticipant create(
            UUID projectId,
            UUID organizationId,
            String partyRole,
            UUID parentParticipantId,
            LocalDate validFrom,
            LocalDate validTo) {
        return new ProjectParticipant(
                UUID.randomUUID(),
                projectId,
                organizationId,
                partyRole,
                parentParticipantId,
                ParticipationStatus.ACTIVE,
                validFrom,
                validTo,
                Instant.now());
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getPartyRole() {
        return partyRole;
    }

    public UUID getParentParticipantId() {
        return parentParticipantId;
    }

    public ParticipationStatus getStatus() {
        return status;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }
}
