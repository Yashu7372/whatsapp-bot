package com.whatsappbot.video.image;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoryboardImageJobRepository extends JpaRepository<StoryboardImageJobEntity, UUID> {
    Optional<StoryboardImageJobEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    List<StoryboardImageJobEntity> findAllByTenantIdAndVideoScriptIdOrderByCreatedAtDesc(
            UUID tenantId, UUID videoScriptId);
}
