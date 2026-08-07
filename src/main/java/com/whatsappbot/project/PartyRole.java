package com.whatsappbot.project;

/**
 * The capacity in which a company takes part in a project.
 *
 * <p>These are delivery roles, not permissions — a user's {@code UserRole} still governs what
 * they may do in the application. The party role answers a different question: which side of the
 * contract the document came from, and therefore who it has to go to next.
 */
public enum PartyRole {

    /** Funds the work and receives the completed asset. */
    CLIENT,

    /** Administers the contract on the client's behalf: designs, reviews, certifies payment. */
    CONSULTANT,

    /** Carries out the work under a direct contract with the client. */
    CONTRACTOR,

    /** Engaged by a contractor rather than the client, so it sits under that contractor. */
    SUBCONTRACTOR
}
