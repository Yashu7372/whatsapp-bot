package com.whatsappbot.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentAuditEventRepository extends JpaRepository<DocumentAuditEventEntity, UUID> {

    List<DocumentAuditEventEntity> findAllByDocumentIdOrderByCreatedAtAsc(UUID documentId);

    @Query("SELECT e FROM DocumentAuditEventEntity e WHERE e.documentId = :documentId ORDER BY e.createdAt DESC LIMIT 1")
    Optional<DocumentAuditEventEntity> findLatestByDocumentId(@Param("documentId") UUID documentId);
}
