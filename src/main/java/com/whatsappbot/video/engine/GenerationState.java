package com.whatsappbot.video.engine;

/**
 * Stable workflow states for the video generation engine.
 *
 * A state is entered only after the gate for that state passes.
 */
public enum GenerationState {
    INTAKE,
    CONTENT_LOCKED,
    AUDIO_LOCKED,
    VISUAL_PLAN_LOCKED,
    PRESENTER_GENERATED,
    COMPOSITION_CHECKED,
    RENDERED,
    VERIFIED,
    FAILED;

    public boolean terminal() {
        return this == VERIFIED || this == FAILED;
    }
}
