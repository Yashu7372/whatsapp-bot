package com.whatsappbot.project;

/** Explicit business capabilities evaluated together with tenant role, project party role and scope. */
public enum ProjectPermission {
    PROJECT_VIEW,

    DOCUMENT_VIEW,
    DOCUMENT_CREATE,
    DOCUMENT_EDIT,
    DOCUMENT_ISSUE,
    DOCUMENT_APPROVE,
    /**
     * Change a document's security classification or administer its access grants. Deliberately
     * separate from DOCUMENT_EDIT: editing content must not confer the authority to declassify a
     * restricted document or to widen who can reach it.
     */
    DOCUMENT_SECURITY_ADMIN,
    DOCUMENT_REVIEW_INTERNAL,
    DOCUMENT_REVIEW_TECHNICAL,
    DOCUMENT_APPROVE_CLIENT,
    DOCUMENT_CERTIFY_COMMERCIAL,

    TRANSMITTAL_VIEW,
    TRANSMITTAL_CREATE,
    TRANSMITTAL_ISSUE,
    TRANSMITTAL_ACKNOWLEDGE,

    COMMERCIAL_VIEW_PROJECT,
    COMMERCIAL_VIEW_ORGANIZATION,
    BUDGET_EDIT_PROJECT,
    FORECAST_SUBMIT_ORGANIZATION,

    PAYMENT_VIEW_PROJECT,
    PAYMENT_VIEW_ORGANIZATION,
    PAYMENT_CREATE_ORGANIZATION,
    PAYMENT_CERTIFY,
    PAYMENT_MARK_PAID
}
