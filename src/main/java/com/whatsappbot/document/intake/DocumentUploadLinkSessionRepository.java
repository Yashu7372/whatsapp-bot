package com.whatsappbot.document.intake;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface DocumentUploadLinkSessionRepository extends JpaRepository<DocumentUploadLinkSessionEntity, UUID> {
    Optional<DocumentUploadLinkSessionEntity> findByToken(String token);

    /** Atomically consumes a still-valid bearer session so it cannot be replayed concurrently. */
    @Modifying(flushAutomatically = true)
    @Query("delete from DocumentUploadLinkSessionEntity s where s.token = :token and s.expiresAt > :now")
    int consumeValid(@Param("token") String token, @Param("now") LocalDateTime now);
}
