package com.whatsappbot.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersionEntity, UUID> {
    List<DocumentVersionEntity> findAllByDocumentIdOrderByVersionNumDesc(UUID documentId);
}
