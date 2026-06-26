package com.whatsappbot.templates;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface VideoTemplateRepository extends JpaRepository<VideoTemplateEntity, UUID> {

    @Query("""
        SELECT t FROM VideoTemplateEntity t
        WHERE t.active = true
          AND (t.scope = 'SYSTEM' OR t.tenant.id = :tenantId)
        ORDER BY t.scope DESC, t.name ASC
        """)
    List<VideoTemplateEntity> findAvailableForTenant(@Param("tenantId") UUID tenantId);

    List<VideoTemplateEntity> findAllByTenantIdAndActiveTrue(UUID tenantId);
}
