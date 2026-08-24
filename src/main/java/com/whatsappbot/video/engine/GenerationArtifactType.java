package com.whatsappbot.video.engine;

/**
 * Typed outputs passed between pipeline stages.
 */
public enum GenerationArtifactType {
    SCRIPT,
    NARRATION_AUDIO,
    WORD_TIMINGS,
    VISUAL_PLAN,
    BROLL,
    CAPTIONS,
    PRESENTER_VIDEO,
    LIP_SYNCED_PRESENTER,
    COMPOSITION_PLAN,
    FINAL_VIDEO,
    QA_REPORT
}
