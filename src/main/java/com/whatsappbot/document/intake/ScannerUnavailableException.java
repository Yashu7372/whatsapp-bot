package com.whatsappbot.document.intake;

/** Thrown when scanning is required (fail-closed) but the scanner could not be reached. */
public class ScannerUnavailableException extends RuntimeException {
    public ScannerUnavailableException(String detail) {
        super("Malware scanner unavailable: " + detail);
    }
}
