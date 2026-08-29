package com.yashu.projectcontrol.scope;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ProjectScopeRepository extends JpaRepository<ProjectScope, UUID> {
    boolean existsByProjectIdAndCodeIgnoreCase(UUID projectId, String code);

    List<ProjectScope> findByProjectIdOrderByCreatedAtAsc(UUID projectId);

    Optional<ProjectScope> findByIdAndProjectId(UUID id, UUID projectId);
}
