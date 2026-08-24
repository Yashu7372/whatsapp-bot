package com.whatsappbot.video.engine.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GenerationJobRepository extends JpaRepository<GenerationJobEntity, UUID> {
    Optional<GenerationJobEntity> findByIdAndTenantId(UUID id, UUID tenantId);
}
