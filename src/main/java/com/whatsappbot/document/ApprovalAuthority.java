package com.whatsappbot.document;

import com.whatsappbot.project.ProjectPermission;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The contractual authority a workflow stage carries, and the project permission it demands.
 *
 * <p>Keeping the mapping here rather than in a switch inside the authorization service means a new
 * authority cannot be added without deciding what permission it requires.
 */
public enum ApprovalAuthority {

    /** The originating company checking its own work before it leaves the office. */
    INTERNAL_REVIEW(ProjectPermission.DOCUMENT_REVIEW_INTERNAL),

    /** The consultant discharging its design-review obligation. */
    TECHNICAL_REVIEW(ProjectPermission.DOCUMENT_REVIEW_TECHNICAL),

    /** The client giving final contractual approval. */
    CLIENT_APPROVAL(ProjectPermission.DOCUMENT_APPROVE_CLIENT),

    /** Certification that the document evidences work with commercial value. */
    COMMERCIAL_CERTIFICATION(ProjectPermission.DOCUMENT_CERTIFY_COMMERCIAL);

    private final ProjectPermission permission;

    ApprovalAuthority(ProjectPermission permission) {
        this.permission = permission;
    }

    public ProjectPermission permission() {
        return permission;
    }

    static ApprovalAuthority of(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Unsupported workflow authority: " + value, ex);
        }
    }
}
