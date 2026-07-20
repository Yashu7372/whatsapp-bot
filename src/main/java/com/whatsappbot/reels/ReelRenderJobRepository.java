package com.whatsappbot.reels;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReelRenderJobRepository extends JpaRepository<ReelRenderJobEntity, UUID> {

    List<ReelRenderJobEntity> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<ReelRenderJobEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<ReelRenderJobEntity> findFirstByStatusOrderByCreatedAtAsc(ReelRenderStatus status);

    @EntityGraph(attributePaths = {"tenant", "videoScript"})
    Optional<ReelRenderJobEntity> findWithVideoScriptById(UUID id);
}
