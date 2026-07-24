package com.whatsappbot.video;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoScriptRepository extends JpaRepository<VideoScriptEntity, UUID> {
    List<VideoScriptEntity> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);
    Optional<VideoScriptEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select script from VideoScriptEntity script where script.id = :id and script.tenant.id = :tenantId")
    Optional<VideoScriptEntity> findForUpdateByIdAndTenantId(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId);
}
