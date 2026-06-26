package com.whatsappbot.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface DocumentAccessGrantRepository extends JpaRepository<DocumentAccessGrantEntity, UUID> {

    List<DocumentAccessGrantEntity> findAllByDocumentId(UUID documentId);

    @Query("""
        SELECT CASE WHEN COUNT(g) > 0 THEN true ELSE false END
        FROM DocumentAccessGrantEntity g
        WHERE g.documentId = :documentId
          AND g.user.id = :userId
          AND g.permissionCode = :permissionCode
          AND (g.expiresAt IS NULL OR g.expiresAt > :now)
        """)
    boolean hasPermission(@Param("documentId") UUID documentId,
                          @Param("userId") UUID userId,
                          @Param("permissionCode") String permissionCode,
                          @Param("now") LocalDateTime now);
}
