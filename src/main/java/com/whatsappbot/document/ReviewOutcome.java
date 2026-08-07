package com.whatsappbot.document;

/**
 * How a reviewer returned a document.
 *
 * <p>Construction review is not a yes/no decision. The convention across UAE contracts is a
 * four-code return, and the two middle codes are the ones that carry the work: CODE_B lets the
 * originator proceed while still owing corrections, and CODE_C sends it back for another cycle
 * without rejecting the submission outright. A plain APPROVED/REJECTED pair cannot express
 * either, which is why the to-and-fro ends up happening over email instead.
 */
public enum ReviewOutcome {

    /** Approved. Work may proceed as submitted. */
    CODE_A(false),

    /** Approved with comments. Work may proceed, but the comments must be incorporated. */
    CODE_B(false),

    /** Revise and resubmit. Work may not proceed; a new revision is required. */
    CODE_C(true),

    /** Rejected. The submission is not acceptable and must be replaced. */
    CODE_D(true);

    private final boolean resubmissionRequired;

    ReviewOutcome(boolean resubmissionRequired) {
        this.resubmissionRequired = resubmissionRequired;
    }

    /** True when the originator has to issue a further revision before work can proceed. */
    public boolean isResubmissionRequired() {
        return resubmissionRequired;
    }
}
