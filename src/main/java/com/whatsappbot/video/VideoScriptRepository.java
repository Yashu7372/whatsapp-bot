package com.whatsappbot.video;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VideoScriptRepository extends JpaRepository<VideoScriptEntity, UUID> {
    List<VideoScriptEntity> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
