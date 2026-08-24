package com.whatsappbot.video.engine;

public record GateResult(
        boolean passed,
        String code,
        String message
) {
    public GateResult {
        code = code == null || code.isBlank() ? (passed ? "PASS" : "REJECTED") : code;
        message = message == null ? "" : message;
    }

    public static GateResult pass(String message) {
        return new GateResult(true, "PASS", message);
    }

    public static GateResult fail(String code, String message) {
        return new GateResult(false, code, message);
    }
}
