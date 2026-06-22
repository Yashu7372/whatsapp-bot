package com.whatsappbot.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentControlWorkflowRepository extends JpaRepository<DocumentControlWorkflowEntity, UUID> {
    List<DocumentControlWorkflowEntity> findAllByTenantIdAndActiveTrue(UUID tenantId);
    Optional<DocumentControlWorkflowEntity> findByTenantIdAndDocType(UUID tenantId, String docType);
}
