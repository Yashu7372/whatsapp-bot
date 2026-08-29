package com.yashu.projectcontrol.workspace;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {
    boolean existsByCodeIgnoreCase(String code);
}
