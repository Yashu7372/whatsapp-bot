package com.whatsappbot.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentApprovalRepository extends JpaRepository<DocumentApprovalEntity, UUID> {
    List<DocumentApprovalEntity> findAllByDocumentIdOrderByStartedAtDesc(UUID documentId);
    Optional<DocumentApprovalEntity> findFirstByDocumentIdAndStatusOrderByStartedAtDesc(UUID documentId, String status);
}
