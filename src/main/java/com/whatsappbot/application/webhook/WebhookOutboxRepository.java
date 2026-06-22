package com.whatsappbot.application.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface WebhookOutboxRepository extends JpaRepository<WebhookOutboxEntity, UUID> {

    @Query(value = """
            SELECT * FROM webhook_outbox
            WHERE status = 'PENDING'
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<WebhookOutboxEntity> findPendingSkipLocked(@Param("limit") int limit);

    // Resets stuck PROCESSING rows that were abandoned by a crashed instance.
    @Modifying
    @Query("""
            UPDATE WebhookOutboxEntity e
            SET e.status = com.whatsappbot.application.webhook.WebhookOutboxStatus.PENDING,
                e.errorMessage = 'reset from stuck PROCESSING'
            WHERE e.status = com.whatsappbot.application.webhook.WebhookOutboxStatus.PROCESSING
              AND e.createdAt < :cutoff
            """)
    int resetStuckProcessing(@Param("cutoff") LocalDateTime cutoff);
}
