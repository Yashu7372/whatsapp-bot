package com.whatsappbot.publishing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PublishResultRepository extends JpaRepository<PublishResultEntity, UUID> {
    Optional<PublishResultEntity> findByJobId(UUID jobId);
}
