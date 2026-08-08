package com.whatsappbot.document.intake;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentUploadLinkSessionRepository extends JpaRepository<DocumentUploadLinkSessionEntity, UUID> {
    Optional<DocumentUploadLinkSessionEntity> findByToken(String token);
}
