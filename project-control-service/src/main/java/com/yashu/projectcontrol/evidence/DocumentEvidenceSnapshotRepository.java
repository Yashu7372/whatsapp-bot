package com.yashu.projectcontrol.evidence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentEvidenceSnapshotRepository extends JpaRepository<DocumentEvidenceSnapshot, UUID> {
    Optional<DocumentEvidenceSnapshot> findTopByRevisionIdOrderByCreatedAtDesc(UUID revisionId);
}
