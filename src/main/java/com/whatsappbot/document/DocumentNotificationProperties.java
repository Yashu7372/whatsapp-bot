package com.whatsappbot.document;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Every tunable of the document notification pipeline in one place.
 *
 * <p>These were previously literals scattered through the service and repository — a ternary chain
 * for the retry ladder, an inline {@code interval '10 minutes'}, a role list repeated inside two
 * SQL branches. Anything an operator might need to change per environment belongs here.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.document-notifications")
public class DocumentNotificationProperties {

    /** Outbox events fanned out per dispatch pass. */
    private int dispatchBatchSize = 50;

    /** Channel deliveries attempted per delivery pass. */
    private int deliveryBatchSize = 50;

    /** Attempts before a delivery is abandoned as DEAD. */
    private int maxAttempts = 5;

    /** Minutes a claim may sit in PROCESSING before a sweeper assumes the claiming pod died. */
    private int staleClaimMinutes = 10;

    /**
     * Backoff ladder in minutes, indexed by attempt number. The last entry repeats for any further
     * attempt, so a shorter list simply plateaus earlier.
     */
    private List<Integer> retryBackoffMinutes = List.of(1, 5, 15, 60);

    /** How long a delivery skipped by a disabled channel waits before becoming eligible again. */
    private int skippedRetryMinutes = 60;

    /** Hours ahead of its due date at which a pending approval step is called "due soon". */
    private int dueSoonHours = 24;

    /** Tenant roles that receive organization- and party-role-targeted notifications. */
    private List<String> notifiableRoles = List.of("ADMIN", "MANAGER", "REVIEWER");

    /** Days a fully settled outbox event is retained before the purge removes it and its children. */
    private int retentionDays = 90;

    private boolean emailEnabled = false;
    private boolean whatsappEnabled = true;
    private String emailFrom = "no-reply@document-control.local";

    /** Minutes of backoff for the given attempt number, clamped to the configured ladder. */
    public int backoffMinutesFor(int attemptNumber) {
        if (retryBackoffMinutes == null || retryBackoffMinutes.isEmpty()) {
            return 1;
        }
        int index = Math.min(Math.max(attemptNumber, 1), retryBackoffMinutes.size()) - 1;
        return retryBackoffMinutes.get(index);
    }
}
