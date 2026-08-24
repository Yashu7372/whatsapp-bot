package com.whatsappbot.video.engine;

/**
 * Replaceable capabilities used by pipeline stages.
 *
 * Providers implement one capability through GenerationAdapter. The engine never
 * depends on a concrete vendor or model.
 */
public enum GenerationCapability {
    CONTENT,
    AUDIO,
    SPEECH_ALIGNMENT,
    VISUAL_PLAN,
    PRESENTER,
    LIP_SYNC,
    COMPOSITION,
    RENDER,
    VERIFY
}
