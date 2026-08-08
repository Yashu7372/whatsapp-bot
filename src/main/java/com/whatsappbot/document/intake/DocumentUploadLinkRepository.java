package com.whatsappbot.document.intake;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentUploadLinkRepository extends JpaRepository<DocumentUploadLinkEntity, UUID> {
    Optional<DocumentUploadLinkEntity> findByToken(String token);
    Optional<DocumentUploadLinkEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    List<DocumentUploadLinkEntity> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    @Modifying
    @Query("update DocumentUploadLinkEntity l set l.uploadCount = l.uploadCount + 1 where l.id = :id")
    void incrementUploadCount(@Param("id") UUID id);
}
