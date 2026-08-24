package com.whatsappbot.video.engine;

public class GateRejectedException extends RuntimeException {

    private final GenerationState targetState;
    private final String gateName;
    private final GateResult result;

    public GateRejectedException(GenerationState targetState, String gateName, GateResult result) {
        super("Gate rejected transition to " + targetState + ": " + result.message());
        this.targetState = targetState;
        this.gateName = gateName;
        this.result = result;
    }

    public GenerationState targetState() {
        return targetState;
    }

    public String gateName() {
        return gateName;
    }

    public GateResult result() {
        return result;
    }
}
