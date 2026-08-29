package com.yashu.projectcontrol.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentWorkflowLinkRepository extends JpaRepository<DocumentWorkflowLink, UUID> {
    List<DocumentWorkflowLink> findByDocumentIdOrderByCreatedAtAsc(UUID documentId);
    Optional<DocumentWorkflowLink> findByWorkflowInstanceId(UUID workflowInstanceId);
}
