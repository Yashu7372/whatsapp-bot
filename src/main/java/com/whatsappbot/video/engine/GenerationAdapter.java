package com.whatsappbot.video.engine;

/**
 * A replaceable implementation for one generation capability.
 *
 * Examples: Kokoro audio, a cloud TTS provider, a local presenter model,
 * a cloud presenter provider, or the existing FFmpeg renderer.
 */
public interface GenerationAdapter {

    GenerationCapability capability();

    String name();

    default int priority() {
        return 100;
    }

    default boolean supports(GenerationContext context) {
        return true;
    }

    StageResult generate(GenerationContext context);
}
