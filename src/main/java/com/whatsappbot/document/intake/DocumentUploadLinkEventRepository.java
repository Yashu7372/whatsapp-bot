package com.whatsappbot.document.intake;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface DocumentUploadLinkEventRepository extends JpaRepository<DocumentUploadLinkEventEntity, UUID> {
    List<DocumentUploadLinkEventEntity> findAllByLinkIdOrderByCreatedAtDesc(UUID linkId);
    long countByLinkIdAndEventTypeAndCreatedAtAfter(UUID linkId, String eventType, LocalDateTime after);
}
