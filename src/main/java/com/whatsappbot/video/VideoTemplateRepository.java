package com.whatsappbot.video;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoTemplateRepository extends JpaRepository<VideoTemplateEntity, UUID> {
    List<VideoTemplateEntity> findAllByActiveTrueAndTenantIsNullOrderByNameAsc();
    List<VideoTemplateEntity> findAllByActiveTrueAndTenantIdOrderByNameAsc(UUID tenantId);
    Optional<VideoTemplateEntity> findFirstByCodeAndTenantIdAndActiveTrue(String code, UUID tenantId);
    Optional<VideoTemplateEntity> findFirstByCodeAndTenantIsNullAndActiveTrue(String code);
}
