package com.whatsappbot.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a claimed outbox event into the per-user records that the channel workers deliver.
 *
 * <p>This is a separate bean from {@link WorkflowNotificationService} on purpose. The scheduler
 * lives there; if the scheduled method called the transactional method on {@code this}, Spring's
 * proxy would be bypassed and {@code @Transactional} would silently do nothing — which is exactly
 * how an outbox row could be marked dispatched while the notifications derived from it were never
 * written. Crossing a bean boundary is what makes the annotation load-bearing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowNotificationDispatcher {

    private final WorkflowNotificationRepository repository;
    private final NotificationMessageFactory messageFactory;
    private final DocumentNotificationProperties properties;
    private final ObjectMapper mapper;

    /**
     * Claims a batch and fans it out inside one transaction. The claim, the in-app rows and the
     * channel deliveries commit together or not at all, so a failure mid-batch returns the events
     * to PENDING instead of losing them.
     */
    @Transactional
    public int dispatchBatch() {
        var events = repository.claimOutbox(properties.getDispatchBatchSize());
        int recipients = 0;
        for (var event : events) {
            NotificationMessageFactory.Message message = messageFactory.build(event.eventType(), payload(event.payload()));
            for (var user : repository.recipients(event, properties.getNotifiableRoles())) {
                repository.insertInApp(event, user, message.subject(), message.body());
                if (properties.isEmailEnabled() && user.emailEnabled() && hasText(user.email())) {
                    repository.insertDelivery(event, user, NotificationChannel.EMAIL, user.email(), message.subject(), message.body());
                }
                if (properties.isWhatsappEnabled() && user.whatsappEnabled() && hasText(user.phone())) {
                    repository.insertDelivery(event, user, NotificationChannel.WHATSAPP, user.phone(), message.subject(), message.body());
                }
                recipients++;
            }
            repository.markOutboxDelivered(event.id());
        }
        return recipients;
    }

    /**
     * Returns events whose claiming pod died before the fan-out committed. Without this an
     * interrupted batch would sit in PROCESSING forever and its recipients would never be told.
     */
    @Transactional
    public int recoverStaleClaims() {
        return repository.recoverStaleOutbox(properties.getStaleClaimMinutes());
    }

    private JsonNode payload(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception ex) {
            log.warn("Unreadable notification payload; rendering with defaults", ex);
            return mapper.createObjectNode();
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
