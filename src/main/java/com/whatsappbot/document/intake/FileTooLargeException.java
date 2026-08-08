package com.whatsappbot.document.intake;

public class FileTooLargeException extends RuntimeException {
    public FileTooLargeException(long limitBytes) {
        super("File exceeds the " + limitBytes + " byte intake limit");
    }
}
