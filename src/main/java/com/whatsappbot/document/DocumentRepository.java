package com.whatsappbot.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {
    List<DocumentEntity> findAllByTenantIdOrderByUpdatedAtDesc(UUID tenantId);
    List<DocumentEntity> findAllByTenantIdAndDocTypeOrderByUpdatedAtDesc(UUID tenantId, String docType);
    Optional<DocumentEntity> findByIdAndTenantId(UUID id, UUID tenantId);
}
