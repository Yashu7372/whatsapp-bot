package com.whatsappbot.document;

/** Lifecycle of a single channel delivery. Persisted by {@link #name()}. */
public enum NotificationDeliveryStatus {
    /** Created, waiting for a worker. */
    PENDING,
    /** Claimed by a worker; released by the stale-claim sweeper if that worker dies. */
    PROCESSING,
    /** Accepted by the channel transport. */
    SENT,
    /** Attempt failed and the row is eligible for another try. */
    FAILED,
    /** Attempts exhausted; no further delivery will be made. */
    DEAD,
    /** Channel was disabled when the attempt ran; retried once the channel is enabled again. */
    SKIPPED
}
