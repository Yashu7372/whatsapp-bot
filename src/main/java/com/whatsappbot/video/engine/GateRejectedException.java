package com.whatsappbot.video.engine;

public class GateRejectedException extends RuntimeException {

    private final GenerationState targetState;
    private final String gateName;
    private final GateResult result;
    private final GenerationContext rejectedContext;

    public GateRejectedException(GenerationState targetState, String gateName,
                                 GateResult result, GenerationContext rejectedContext) {
        super("Gate rejected transition to " + targetState + ": " + result.message());
        this.targetState = targetState;
        this.gateName = gateName;
        this.result = result;
        this.rejectedContext = rejectedContext;
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

    public GenerationContext rejectedContext() {
        return rejectedContext;
    }
}
