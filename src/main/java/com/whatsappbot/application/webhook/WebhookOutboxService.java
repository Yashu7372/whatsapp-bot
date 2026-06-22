package com.whatsappbot.application.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookOutboxService {

    private final WebhookOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void enqueue(JsonNode payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            outboxRepository.save(WebhookOutboxEntity.pending(json));
        } catch (JsonProcessingException e) {
            // Raw payload already came in as JSON — this should never happen.
            throw new IllegalStateException("Failed to serialize inbound webhook payload", e);
        }
    }

    /**
     * Claims PENDING rows atomically. Uses SKIP LOCKED so concurrent processors
     * on other Cloud Run instances do not pick the same rows.
     * Must be called inside a transaction so the FOR UPDATE lock is held until commit.
     */
    @Transactional
    public List<WebhookOutboxEntity> claimForProcessing(int batchSize) {
        List<WebhookOutboxEntity> rows = outboxRepository.findPendingSkipLocked(batchSize);
        rows.forEach(r -> r.setStatus(WebhookOutboxStatus.PROCESSING));
        outboxRepository.saveAll(rows);
        return rows;
    }

    @Transactional
    public void markDone(UUID id) {
        outboxRepository.findById(id).ifPresent(e -> {
            e.setStatus(WebhookOutboxStatus.DONE);
            e.setProcessedAt(LocalDateTime.now());
        });
    }

    @Transactional
    public void markFailed(UUID id, int newRetryCount, String errorMessage, int maxRetries) {
        outboxRepository.findById(id).ifPresent(e -> {
            e.setRetryCount(newRetryCount);
            e.setErrorMessage(errorMessage);
            if (newRetryCount >= maxRetries) {
                e.setStatus(WebhookOutboxStatus.FAILED);
                e.setProcessedAt(LocalDateTime.now());
                log.error("Webhook outbox event permanently failed after {} retries. id={}", maxRetries, id);
            } else {
                e.setStatus(WebhookOutboxStatus.PENDING);
            }
        });
    }

    @Transactional
    public int resetStuckProcessing(int stuckMinutes) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(stuckMinutes);
        int count = outboxRepository.resetStuckProcessing(cutoff);
        if (count > 0) {
            log.warn("Reset {} stuck PROCESSING webhook outbox events older than {} minutes", count, stuckMinutes);
        }
        return count;
    }
}
