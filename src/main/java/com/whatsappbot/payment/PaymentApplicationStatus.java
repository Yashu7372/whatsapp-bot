package com.whatsappbot.payment;

/**
 * Lifecycle of a payment application.
 *
 * <p>Only DRAFT is editable. Once submitted the claim is a contractual position, so the figures
 * are fixed and any change has to come through a fresh application.
 */
public enum PaymentApplicationStatus {

    /** Being assembled by the claiming party; lines may still be added or removed. */
    DRAFT,

    /** Issued to the certifying party and awaiting their assessment. */
    SUBMITTED,

    /** Assessed and certified for payment. */
    CERTIFIED,

    /** Assessed and declined. */
    REJECTED,

    /** Certified and settled. */
    PAID;

    /** True while the claim can still be edited. */
    public boolean isEditable() {
        return this == DRAFT;
    }
}
