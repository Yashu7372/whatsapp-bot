package com.whatsappbot.video.engine;

/**
 * Validation checkpoint executed before the engine enters a target state.
 */
public interface GenerationGate {

    String name();

    boolean supports(GenerationState targetState, GenerationContext context);

    GateResult validate(GenerationState targetState, GenerationContext context);
}
