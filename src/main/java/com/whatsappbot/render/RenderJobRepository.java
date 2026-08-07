package com.whatsappbot.render;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RenderJobRepository extends JpaRepository<RenderJobEntity, UUID> {

    List<RenderJobEntity> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<RenderJobEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    List<RenderJobEntity> findAllByContentItemIdAndTenantId(UUID contentItemId, UUID tenantId);
}
