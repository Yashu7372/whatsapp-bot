package com.whatsappbot.document;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import com.whatsappbot.infrastructure.whatsapp.WhatsAppGraphClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the delivery worker's failure handling — the part that decides whether a notification is
 * retried, parked or abandoned.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowNotificationDeliveryTest {

    @Mock WorkflowNotificationRepository repository;
    @Mock WorkflowNotificationDispatcher dispatcher;
    @Mock WorkflowNotificationEventService eventService;
    @Mock TenantRepository tenantRepository;
    @Mock WhatsAppGraphClient whatsApp;
    @Mock JavaMailSender mailSender;

    DocumentNotificationProperties properties;
    WorkflowNotificationService service;

    final UUID tenant = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new DocumentNotificationProperties();
        service = new WorkflowNotificationService(repository, dispatcher, eventService, properties,
                tenantRepository, whatsApp, mailSender);
    }

    @Test
    @DisplayName("a disabled channel parks the delivery for retry instead of consuming an attempt")
    void disabledChannelIsSkippedNotFailed() {
        properties.setEmailEnabled(false);
        when(repository.claimDeliveries(anyInt(), anyInt())).thenReturn(List.of(delivery(NotificationChannel.EMAIL, 0)));

        service.deliverBatch();

        // SKIPPED with a retry window: enabling email later must not leave these stranded.
        verify(repository).skipped(any(), anyString(), eq(properties.getSkippedRetryMinutes()));
        verify(repository, never()).failed(any(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean(), anyInt(), anyString());
    }

    @Test
    @DisplayName("a WhatsApp transport error is retried with backoff, not swallowed")
    void whatsAppFailureIsRetried() {
        properties.setWhatsappEnabled(true);
        when(tenantRepository.findById(tenant)).thenReturn(Optional.of(new TenantEntity()));
        doThrow(new IllegalStateException("WhatsApp Graph API returned HTTP 500"))
                .when(whatsApp).sendTextMessageChecked(any(), anyString(), anyString());
        when(repository.claimDeliveries(anyInt(), anyInt())).thenReturn(List.of(delivery(NotificationChannel.WHATSAPP, 0)));

        service.deliverBatch();

        verify(repository).failed(any(), eq(1), eq(false), eq(properties.backoffMinutesFor(1)), anyString());
        verify(repository, never()).sent(any());
    }

    @Test
    @DisplayName("the final attempt marks the delivery dead rather than retrying forever")
    void exhaustedAttemptsBecomeDead() {
        properties.setWhatsappEnabled(true);
        when(tenantRepository.findById(tenant)).thenReturn(Optional.of(new TenantEntity()));
        doThrow(new IllegalStateException("still failing"))
                .when(whatsApp).sendTextMessageChecked(any(), anyString(), anyString());
        int lastAttempt = properties.getMaxAttempts() - 1;
        when(repository.claimDeliveries(anyInt(), anyInt()))
                .thenReturn(List.of(delivery(NotificationChannel.WHATSAPP, lastAttempt)));

        service.deliverBatch();

        verify(repository).failed(any(), eq(properties.getMaxAttempts()), eq(true), anyInt(), anyString());
    }

    @Test
    @DisplayName("the backoff ladder plateaus instead of running off the end")
    void backoffLadderIsBounded() {
        assertThat(properties.backoffMinutesFor(1)).isEqualTo(1);
        assertThat(properties.backoffMinutesFor(4)).isEqualTo(60);
        assertThat(properties.backoffMinutesFor(99)).isEqualTo(60);
        assertThat(properties.backoffMinutesFor(0)).isEqualTo(1);
    }

    private WorkflowNotificationRepository.DeliveryRow delivery(NotificationChannel channel, int attempts) {
        return new WorkflowNotificationRepository.DeliveryRow(UUID.randomUUID(), tenant, UUID.randomUUID(),
                UUID.randomUUID(), channel, "destination", "subject", "body", attempts);
    }
}
