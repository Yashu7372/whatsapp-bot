package com.whatsappbot.reels;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReelRenderJobRepository extends JpaRepository<ReelRenderJobEntity, UUID> {

    List<ReelRenderJobEntity> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<ReelRenderJobEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<ReelRenderJobEntity> findFirstByStatusOrderByCreatedAtAsc(ReelRenderStatus status);

    @Query("select job from ReelRenderJobEntity job " +
            "join fetch job.tenant " +
            "join fetch job.videoScript " +
            "where job.id = :id")
    Optional<ReelRenderJobEntity> findWithVideoScriptById(@Param("id") UUID id);
}
