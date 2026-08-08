package com.whatsappbot.document;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Security scope of a controlled document. Persisted by {@link #name()} and constrained by
 * {@code ck_document_security}.
 *
 * <p>Previously a bare string compared in a switch with a {@code default -> false} arm, which meant
 * an unrecognised value silently denied every request rather than being reported as the data fault
 * it is.
 */
public enum DocumentClassification {
    /** Readable by every active project participant; edited by the originator or a grant holder. */
    PROJECT,
    /** Readable by the originating company, explicit grants and assigned reviewers. */
    ORGANIZATION,
    /** Readable only through an explicit grant or a workflow assignment. */
    RESTRICTED;

    static DocumentClassification of(String value, UUID documentId) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Document has an unrecognised security classification (" + value + "): " + documentId, ex);
        }
    }
}
