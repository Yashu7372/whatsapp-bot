package com.yashu.projectcontrol.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRevisionRepository extends JpaRepository<DocumentRevision, UUID> {
    List<DocumentRevision> findByDocumentIdOrderBySequenceNumberAsc(UUID documentId);
    Optional<DocumentRevision> findByIdAndDocumentId(UUID id, UUID documentId);
    boolean existsByDocumentIdAndRevisionCodeIgnoreCase(UUID documentId, String revisionCode);
}
