package com.yashu.projectcontrol.participation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ProjectParticipantRepository extends JpaRepository<ProjectParticipant, UUID> {
    List<ProjectParticipant> findByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);

    List<ProjectParticipant> findByProjectIdOrderByCreatedAtAsc(UUID projectId);

    Optional<ProjectParticipant> findByIdAndProjectId(UUID id, UUID projectId);
}
