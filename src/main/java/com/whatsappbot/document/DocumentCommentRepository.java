package com.whatsappbot.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentCommentRepository extends JpaRepository<DocumentCommentEntity, UUID> {
    List<DocumentCommentEntity> findAllByDocumentIdOrderByCreatedAtAsc(UUID documentId);
}
