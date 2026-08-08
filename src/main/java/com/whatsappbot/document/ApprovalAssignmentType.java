package com.whatsappbot.document;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** How a workflow stage identifies the party expected to decide it. */
public enum ApprovalAssignmentType {
    /** A named individual, matched by email. */
    USER,
    /** A specific participating company; any notifiable member of it may decide. */
    ORGANIZATION,
    /** Whichever companies act under this party role on the project. */
    PARTY_ROLE;

    static ApprovalAssignmentType of(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Unsupported workflow assignment type: " + value, ex);
        }
    }
}
