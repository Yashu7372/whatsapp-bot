package com.yashu.projectcontrol.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface ProjectRepository extends JpaRepository<Project, UUID> {
    boolean existsByWorkspaceIdAndCodeIgnoreCase(UUID workspaceId, String code);
}
