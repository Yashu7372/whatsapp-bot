package com.whatsappbot.jobs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface BackgroundJobRepository extends JpaRepository<BackgroundJobEntity, UUID> {

    @Query("""
        SELECT j FROM BackgroundJobEntity j
        WHERE j.status IN ('PENDING', 'RETRYING')
          AND j.runAfter <= :now
          AND j.lockedBy IS NULL
        ORDER BY j.priority DESC, j.runAfter ASC
        LIMIT :limit
        """)
    List<BackgroundJobEntity> findDueJobs(@Param("now") LocalDateTime now, @Param("limit") int limit);

    List<BackgroundJobEntity> findAllByTenantIdAndJobTypeOrderByCreatedAtDesc(UUID tenantId, String jobType);
}
