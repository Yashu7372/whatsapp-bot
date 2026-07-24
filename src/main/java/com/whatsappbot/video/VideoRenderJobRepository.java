package com.whatsappbot.video;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoRenderJobRepository extends JpaRepository<VideoRenderJobEntity, UUID> {
    Optional<VideoRenderJobEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    List<VideoRenderJobEntity> findAllByTenantIdAndVideoScriptIdOrderByCreatedAtDesc(
            UUID tenantId, UUID videoScriptId);
}
