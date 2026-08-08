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

    /**
     * Atomically reserves one upload slot. This removes the check-then-increment race that could
     * let concurrent requests exceed maxUploads. A failed surrounding transaction rolls the
     * reservation back together with the document write.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update DocumentUploadLinkEntity l
               set l.uploadCount = l.uploadCount + 1
             where l.id = :id
               and l.revokedAt is null
               and l.expiresAt > CURRENT_TIMESTAMP
               and (l.maxUploads is null or l.uploadCount < l.maxUploads)
            """)
    int tryReserveUploadSlot(@Param("id") UUID id);
}
