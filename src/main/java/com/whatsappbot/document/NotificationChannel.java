package com.whatsappbot.document;

/** Delivery channels backing a document notification. Persisted by {@link #name()}. */
public enum NotificationChannel {
    EMAIL,
    WHATSAPP;

    static NotificationChannel of(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalStateException("Unsupported notification channel: " + value, ex);
        }
    }
}
