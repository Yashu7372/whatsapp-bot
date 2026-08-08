package com.whatsappbot.document.scan;

/** Result of submitting one file to the malware scanner. */
public enum ScanOutcome {
    CLEAN,
    INFECTED,
    /** Scanner disabled or unreachable — the caller applies its own fail-open/fail-closed policy. */
    UNAVAILABLE
}
