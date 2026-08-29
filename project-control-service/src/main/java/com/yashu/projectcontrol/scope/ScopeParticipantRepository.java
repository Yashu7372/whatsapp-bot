package com.yashu.projectcontrol.scope;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface ScopeParticipantRepository extends JpaRepository<ScopeParticipant, UUID> {
    boolean existsByScopeIdAndProjectParticipantId(UUID scopeId, UUID projectParticipantId);

    List<ScopeParticipant> findByProjectParticipantIdOrderByCreatedAtAsc(UUID projectParticipantId);
}
