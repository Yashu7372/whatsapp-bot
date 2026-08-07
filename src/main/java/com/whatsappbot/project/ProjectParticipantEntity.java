package com.whatsappbot.project;

import com.whatsappbot.domain.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One company's place on one project.
 *
 * <p>{@code parentParticipant} is the inter-company link. A subcontractor is engaged by a
 * contractor rather than by the client, so its participant row points at that contractor's row.
 * Walking the links upward gives the chain of responsibility for a document, which is what
 * decides who a submission is answerable to.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "project_participants")
public class ProjectParticipantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private OrganizationEntity organization;

    @Enumerated(EnumType.STRING)
    @Column(name = "party_role", nullable = false, length = 50)
    private PartyRole partyRole;

    /** The participant that engaged this one; null for parties in direct contract with the client. */
    @Column(name = "parent_participant_id")
    private UUID parentParticipantId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
