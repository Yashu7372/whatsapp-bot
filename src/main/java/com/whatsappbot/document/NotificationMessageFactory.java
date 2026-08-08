package com.whatsappbot.document;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Renders the customer-facing subject and body of a notification.
 *
 * <p>The copy lives in {@code messages.properties}, not in Java string literals, so business text
 * can be changed or translated without a rebuild — the same rule the rest of this codebase applies
 * to configuration.
 */
@Component
@RequiredArgsConstructor
public class NotificationMessageFactory {

    private static final String SUBJECT_SUFFIX = ".subject";
    private static final String BODY_SUFFIX = ".body";
    private static final String KEY_PREFIX = "document.notification.";
    private static final String FALLBACK_KEY = KEY_PREFIX + "default";

    private final MessageSource messages;

    public Message build(String eventType, JsonNode payload) {
        String key = KEY_PREFIX + eventType.toLowerCase();
        Object[] args = {
                text(payload, "documentCode", text(payload, "title", label("document"))),
                text(payload, "stepName", label("stage")),
                text(payload, "dueAt", ""),
                text(payload, "status", ""),
                text(payload, "reviewOutcome", ""),
                text(payload, "transmittalNo", ""),
                text(payload, "subject", ""),
                text(payload, "purpose", "")
        };
        return new Message(resolve(key + SUBJECT_SUFFIX, args, eventType),
                resolve(key + BODY_SUFFIX, args, eventType));
    }

    private String resolve(String key, Object[] args, String eventType) {
        String resolved = messages.getMessage(key, args, null, LocaleContextHolder.getLocale());
        if (resolved != null) {
            return resolved;
        }
        // An event type without dedicated copy still produces a readable message rather than a key.
        return messages.getMessage(FALLBACK_KEY + (key.endsWith(SUBJECT_SUFFIX) ? SUBJECT_SUFFIX : BODY_SUFFIX),
                new Object[]{eventType.replace('_', ' '), args[0]}, LocaleContextHolder.getLocale());
    }

    private String label(String name) {
        return messages.getMessage(KEY_PREFIX + "label." + name, null, name, LocaleContextHolder.getLocale());
    }

    private static String text(JsonNode payload, String field, String fallback) {
        JsonNode node = payload.get(field);
        return node == null || node.isNull() || node.asText().isBlank() ? fallback : node.asText();
    }

    public record Message(String subject, String body) {}
}
