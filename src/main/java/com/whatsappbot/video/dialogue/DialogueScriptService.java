package com.whatsappbot.video.dialogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Uses the configured LangChain4j ChatModel to generate a structured dialogue script.
 * All LLM calls run here; the heavy rendering work is delegated to the renderer worker.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DialogueScriptService {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public DialogueScript generate(String topic, int maxTurns) {
        String prompt = buildPrompt(topic, Math.max(4, Math.min(maxTurns, 20)));
        String raw;
        try {
            raw = chatModel.chat(prompt);
        } catch (Exception e) {
            log.warn("LLM call failed for dialogue script, using fallback. topic={} error={}", topic, e.getMessage());
            return buildFallback(topic);
        }
        return parse(raw, topic);
    }

    // ------------------------------------------------------------------
    // Prompt
    // ------------------------------------------------------------------

    private String buildPrompt(String topic, int maxTurns) {
        return """
                You are writing a short educational dialogue between two characters:

                BHAIYA — an older Indian boy, about 10 years old, who explains things in a
                simple, enthusiastic, relatable way using everyday analogies.

                CHITTI — a curious Indian baby/toddler girl, about 1-2 years old, who asks
                simple questions like "Why?", "How?", "Ohhh!", and reacts with wonder.

                Topic to explain: %s

                Write a natural dialogue of exactly %d turns that teaches this topic simply.
                - Bhaiya uses short sentences (max 15 words), simple words, one analogy.
                - Chitti says very short things (max 8 words), mostly questions or reactions.
                - Alternate speakers (start with Chitti asking a question).
                - duration_seconds = estimated time to speak the text naturally (1 word ≈ 0.4s).

                Return ONLY valid JSON, no markdown, no explanation:
                {
                  "topic": "%s",
                  "turns": [
                    {"speaker": "chitti", "emotion": "curious",   "text": "...", "duration_seconds": 2.5},
                    {"speaker": "bhaiya", "emotion": "talking",   "text": "...", "duration_seconds": 4.0},
                    ...
                  ]
                }

                Valid emotions: idle, talking, curious, surprised, laughing, thinking
                Valid speakers: bhaiya, chitti
                """.formatted(topic, maxTurns, topic);
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private DialogueScript parse(String raw, String topic) {
        String json = stripMarkdown(raw);
        try {
            JsonNode root = objectMapper.readTree(json);
            List<DialogueTurn> turns = new ArrayList<>();
            JsonNode turnsNode = root.path("turns");
            if (turnsNode.isArray()) {
                for (JsonNode t : turnsNode) {
                    String speaker = t.path("speaker").asText("bhaiya");
                    String emotion = t.path("emotion").asText("talking");
                    String text    = t.path("text").asText("").trim();
                    double dur     = t.path("duration_seconds").asDouble(4.0);
                    if (!text.isBlank()) {
                        turns.add(new DialogueTurn(speaker, emotion, text, dur));
                    }
                }
            }
            if (turns.isEmpty()) {
                return buildFallback(topic);
            }
            return new DialogueScript(root.path("topic").asText(topic), turns);
        } catch (Exception e) {
            log.warn("Failed to parse dialogue script JSON; using fallback. error={}", e.getMessage());
            return buildFallback(topic);
        }
    }

    private DialogueScript buildFallback(String topic) {
        return new DialogueScript(topic, List.of(
                new DialogueTurn("chitti", "curious",  "What is " + topic + "?",                    2.5),
                new DialogueTurn("bhaiya", "talking",  topic + " is something really cool!",         3.5),
                new DialogueTurn("chitti", "surprised", "Ohhh!",                                     1.5),
                new DialogueTurn("bhaiya", "laughing", "Yes! And I will tell you all about it.",     3.5)
        ));
    }

    private static String stripMarkdown(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            t = t.replaceAll("(?s)^```\\w*\\n?", "").replaceAll("```$", "").trim();
        }
        int start = t.indexOf('{');
        int end   = t.lastIndexOf('}');
        return (start >= 0 && end > start) ? t.substring(start, end + 1) : t;
    }
}
