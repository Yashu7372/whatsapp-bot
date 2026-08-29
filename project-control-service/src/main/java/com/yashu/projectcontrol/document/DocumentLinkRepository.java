package com.yashu.projectcontrol.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentLinkRepository extends JpaRepository<DocumentLink, UUID> {
    List<DocumentLink> findByDocumentIdOrderByCreatedAtAsc(UUID documentId);
}
