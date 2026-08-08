package com.whatsappbot.document;

import com.whatsappbot.domain.tenant.TenantRepository;
import com.whatsappbot.infrastructure.whatsapp.WhatsAppGraphClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Schedules the notification pipeline and performs channel delivery.
 *
 * <p>The scheduled methods here hold no transaction. Fan-out is delegated to
 * {@link WorkflowNotificationDispatcher} across a bean boundary so its {@code @Transactional} is
 * actually applied; delivery deliberately stays outside a transaction because it performs network
 * I/O and must not hold a database connection open for the duration of an SMTP or Graph API call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowNotificationService {

    private final WorkflowNotificationRepository repository;
    private final WorkflowNotificationDispatcher dispatcher;
    private final WorkflowNotificationEventService eventService;
    private final DocumentNotificationProperties properties;
    private final TenantRepository tenantRepository;
    private final WhatsAppGraphClient whatsApp;
    private final JavaMailSender mailSender;

    @Scheduled(fixedDelayString = "${app.document-notifications.sla-scan-ms:600000}")
    public void scanSla() {
        try {
            eventService.enqueueSlaNotifications(properties.getDueSoonHours());
        } catch (Exception ex) {
            log.error("Document notification SLA scan failed", ex);
        }
    }

    @Scheduled(fixedDelayString = "${app.document-notifications.dispatch-ms:15000}")
    public void dispatch() {
        try {
            dispatcher.recoverStaleClaims();
            dispatcher.dispatchBatch();
        } catch (Exception ex) {
            log.error("Document notification audience dispatch failed", ex);
        }
    }

    @Scheduled(fixedDelayString = "${app.document-notifications.delivery-ms:30000}")
    public void deliver() {
        try {
            repository.recoverStuckDeliveries(properties.getStaleClaimMinutes());
            deliverBatch();
        } catch (Exception ex) {
            log.error("Document notification delivery worker failed", ex);
        }
    }

    @Scheduled(fixedDelayString = "${app.document-notifications.purge-ms:86400000}")
    public void purge() {
        try {
            int removed = repository.purgeSettled(properties.getRetentionDays());
            if (removed > 0) {
                log.info("Purged {} settled document notification events older than {} days",
                        removed, properties.getRetentionDays());
            }
        } catch (Exception ex) {
            log.error("Document notification retention purge failed", ex);
        }
    }

    public int deliverBatch() {
        int processed = 0;
        for (var delivery : repository.claimDeliveries(properties.getDeliveryBatchSize(), properties.getMaxAttempts())) {
            try {
                switch (delivery.channel()) {
                    case EMAIL -> sendEmail(delivery);
                    case WHATSAPP -> sendWhatsApp(delivery);
                }
                repository.sent(delivery.id());
            } catch (ChannelDisabledException disabled) {
                repository.skipped(delivery.id(), disabled.getMessage(), properties.getSkippedRetryMinutes());
            } catch (Exception ex) {
                int attemptNumber = delivery.attempts() + 1;
                boolean exhausted = attemptNumber >= properties.getMaxAttempts();
                repository.failed(delivery.id(), attemptNumber, exhausted,
                        properties.backoffMinutesFor(attemptNumber), rootMessage(ex));
                log.warn("Notification delivery failed. id={} channel={} attempt={} exhausted={}",
                        delivery.id(), delivery.channel(), attemptNumber, exhausted, ex);
            }
            processed++;
        }
        return processed;
    }

    private void sendEmail(WorkflowNotificationRepository.DeliveryRow d) {
        if (!properties.isEmailEnabled()) {
            throw new ChannelDisabledException("Email delivery disabled; set DOCUMENT_NOTIFICATION_EMAIL_ENABLED=true and SMTP settings");
        }
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(properties.getEmailFrom());
        m.setTo(d.destination());
        m.setSubject(d.subject());
        m.setText(d.body());
        mailSender.send(m);
    }

    private void sendWhatsApp(WorkflowNotificationRepository.DeliveryRow d) {
        if (!properties.isWhatsappEnabled()) {
            throw new ChannelDisabledException("WhatsApp document notifications disabled");
        }
        var tenant = tenantRepository.findById(d.tenantId())
                .orElseThrow(() -> new IllegalStateException("Tenant not found: " + d.tenantId()));
        whatsApp.sendTextMessageChecked(tenant, d.destination(), d.subject() + "\n\n" + d.body());
    }

    private static String rootMessage(Throwable t) {
        Throwable x = t;
        while (x.getCause() != null) x = x.getCause();
        return x.getMessage() == null ? x.getClass().getSimpleName() : x.getMessage();
    }

    private static final class ChannelDisabledException extends RuntimeException {
        ChannelDisabledException(String message) { super(message); }
    }

    public List<WorkflowNotificationRepository.InAppView> mine(UUID tenantId, UUID userId, int limit) {
        return repository.notifications(tenantId, userId, Math.max(1, Math.min(limit, 200)));
    }

    public int unread(UUID tenantId, UUID userId) { return repository.unreadCount(tenantId, userId); }

    public int markRead(UUID tenantId, UUID userId, UUID notificationId) {
        return repository.markRead(tenantId, userId, notificationId);
    }

    public int markAllRead(UUID tenantId, UUID userId) { return repository.markAllRead(tenantId, userId); }

    public WorkflowNotificationRepository.Preferences preferences(UUID tenantId, UUID userId) {
        return repository.preferences(tenantId, userId);
    }

    public void preferences(UUID tenantId, UUID userId, boolean email, boolean whatsapp, String phone) {
        repository.updatePreferences(tenantId, userId, email, whatsapp, phone);
    }

    public List<WorkflowNotificationRepository.DeliveryAudit> audit(UUID tenantId, int limit) {
        return repository.deliveryAudit(tenantId, Math.max(1, Math.min(limit, 500)));
    }
}
