package com.whatsappbot.video.dialogue;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One spoken turn in a two-character dialogue.
 * Speakers are always "bhaiya" (explainer) or "chitti" (learner).
 */
public record DialogueTurn(
        @JsonProperty("speaker") String speaker,
        @JsonProperty("emotion") String emotion,
        @JsonProperty("text") String text,
        @JsonProperty("duration_seconds") double durationSeconds
) {
    public DialogueTurn {
        if (!speaker.equals("bhaiya") && !speaker.equals("chitti")) {
            throw new IllegalArgumentException("speaker must be 'bhaiya' or 'chitti', got: " + speaker);
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        if (durationSeconds < 1.0) {
            durationSeconds = 4.0;
        }
    }
}
