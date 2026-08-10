package com.whatsappbot.video;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RenderJobRepository extends JpaRepository<RenderJobEntity, UUID> {

    List<RenderJobEntity> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<RenderJobEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    @Query(value = """
            SELECT * FROM video_render_jobs
            WHERE status = 'QUEUED'
              AND next_attempt_at <= now()
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<RenderJobEntity> findQueuedSkipLocked(@Param("limit") int limit);

    @Modifying
    @Query("""
            UPDATE RenderJobEntity j
            SET j.status = com.whatsappbot.video.RenderJobStatus.QUEUED,
                j.progress = 0,
                j.startedAt = null,
                j.errorMessage = 'reset from stuck PROCESSING',
                j.nextAttemptAt = :now
            WHERE j.status = com.whatsappbot.video.RenderJobStatus.PROCESSING
              AND j.startedAt < :cutoff
            """)
    int resetStuckProcessing(@Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now);
}
