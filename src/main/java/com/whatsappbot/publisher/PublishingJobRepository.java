package com.whatsappbot.publisher;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PublishingJobRepository extends JpaRepository<PublishingJobEntity, UUID> {

    List<PublishingJobEntity> findAllByTenantIdOrderByScheduledAtDesc(UUID tenantId);

    Optional<PublishingJobEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    @Query("""
        SELECT j FROM PublishingJobEntity j
        WHERE j.status IN ('SCHEDULED', 'RETRYING')
          AND j.scheduledAt <= :now
        ORDER BY j.scheduledAt ASC
        LIMIT :limit
        """)
    List<PublishingJobEntity> findDueJobs(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
