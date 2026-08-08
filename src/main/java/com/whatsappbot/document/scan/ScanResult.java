package com.whatsappbot.document.scan;

/** signature is populated only when outcome is INFECTED; detail carries an error message for UNAVAILABLE. */
public record ScanResult(ScanOutcome outcome, String signature, String detail) {
    public static ScanResult clean() {
        return new ScanResult(ScanOutcome.CLEAN, null, null);
    }

    public static ScanResult infected(String signature) {
        return new ScanResult(ScanOutcome.INFECTED, signature, null);
    }

    public static ScanResult unavailable(String detail) {
        return new ScanResult(ScanOutcome.UNAVAILABLE, null, detail);
    }
}
