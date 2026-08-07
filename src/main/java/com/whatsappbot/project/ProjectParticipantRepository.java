package com.whatsappbot.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectParticipantRepository extends JpaRepository<ProjectParticipantEntity, UUID> {

    List<ProjectParticipantEntity> findAllByTenantIdAndProjectIdOrderByPartyRoleAsc(UUID tenantId,
                                                                                     UUID projectId);

    List<ProjectParticipantEntity> findAllByTenantIdAndProjectIdAndActiveTrueOrderByPartyRoleAsc(
            UUID tenantId, UUID projectId);

    Optional<ProjectParticipantEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Participants engaged by the given one — the subcontractors beneath a contractor. */
    List<ProjectParticipantEntity> findAllByTenantIdAndParentParticipantId(UUID tenantId,
                                                                           UUID parentParticipantId);

    boolean existsByProjectIdAndOrganizationIdAndPartyRole(UUID projectId, UUID organizationId,
                                                           PartyRole partyRole);

    /** Used to check whether a user's company is entitled to see a project's documents. */
    boolean existsByTenantIdAndProjectIdAndOrganizationIdAndActiveTrue(UUID tenantId, UUID projectId,
                                                                        UUID organizationId);
}
