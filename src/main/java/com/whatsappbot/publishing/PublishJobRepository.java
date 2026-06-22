package com.whatsappbot.publishing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface PublishJobRepository extends JpaRepository<PublishJobEntity, UUID> {
    List<PublishJobEntity> findAllByTenantIdOrderByScheduledAtDesc(UUID tenantId);
    List<PublishJobEntity> findAllByStatusAndScheduledAtBefore(PublishStatus status, LocalDateTime cutoff);
}
