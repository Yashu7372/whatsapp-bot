package com.whatsappbot.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<ProjectEntity, UUID> {

    List<ProjectEntity> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<ProjectEntity> findAllByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status);

    Optional<ProjectEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndProjectCodeIgnoreCase(UUID tenantId, String projectCode);
}
