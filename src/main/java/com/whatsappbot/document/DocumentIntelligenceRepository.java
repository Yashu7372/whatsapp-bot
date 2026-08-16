package com.whatsappbot.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentIntelligenceRepository extends JpaRepository<DocumentIntelligenceEntity, UUID> {
    Optional<DocumentIntelligenceEntity> findTopByTenantIdAndDocumentIdOrderByVersionNumDesc(UUID tenantId, UUID documentId);
    Optional<DocumentIntelligenceEntity> findByTenantIdAndDocumentIdAndVersionNum(UUID tenantId, UUID documentId, int versionNum);
}
