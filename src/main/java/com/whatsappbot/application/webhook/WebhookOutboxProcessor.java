package com.whatsappbot.application.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookOutboxProcessor {

    private final WebhookOutboxService outboxService;
    private final WebhookApplicationService webhookApplicationService;
    private final ObjectMapper objectMapper;

    @Value("${app.webhook.outbox.batch-size:5}")
    private int batchSize;

    @Value("${app.webhook.outbox.max-retries:3}")
    private int maxRetries;

    @Value("${app.webhook.outbox.stuck-recovery-minutes:10}")
    private int stuckRecoveryMinutes;

    @Scheduled(fixedDelayString = "${app.webhook.outbox.poll-interval-ms:1000}")
    public void poll() {
        List<WebhookOutboxEntity> batch = outboxService.claimForProcessing(batchSize);
        if (batch.isEmpty()) {
            return;
        }
        log.debug("Processing {} webhook outbox events", batch.size());
        for (WebhookOutboxEntity event : batch) {
            processEvent(event);
        }
    }

    // Runs every minute to recover events stuck in PROCESSING due to a crashed instance.
    @Scheduled(fixedDelay = 60_000)
    public void recoverStuck() {
        outboxService.resetStuckProcessing(stuckRecoveryMinutes);
    }

    private void processEvent(WebhookOutboxEntity event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload());
            webhookApplicationService.handleIncomingWebhook(payload);
            outboxService.markDone(event.getId());
        } catch (Exception e) {
            log.error("Webhook outbox processing failed. id={}, attempt={}", event.getId(), event.getRetryCount() + 1, e);
            outboxService.markFailed(event.getId(), event.getRetryCount() + 1, e.getMessage(), maxRetries);
        }
    }
}
