package com.whatsappbot.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.domain.tenant.TenantEntity;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoScriptService {

    private final VideoScriptRepository videoScriptRepository;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public VideoScriptEntity generate(TenantEntity tenant, String topic, String platformCode,
                                      String contentType, String style, int durationSecs,
                                      String templateCode, UUID contentIdeaId) {
        String prompt = buildPrompt(topic, platformCode, contentType, style, durationSecs, templateCode);
        String raw;
        try {
            raw = chatModel.chat(prompt);
        } catch (Exception e) {
            log.warn("AI video script generation failed, using fallback: {}", e.getMessage());
            raw = buildFallback(topic, platformCode, durationSecs);
        }

        String json = stripMarkdown(raw);
        VideoScriptEntity script = new VideoScriptEntity();
        script.setTenant(tenant);
        script.setPlatformCode(platformCode);
        script.setContentType(contentType);
        script.setStyle(style);
        script.setTemplateCode(templateCode);
        script.setDurationSecs(durationSecs);
        script.setContentIdeaId(contentIdeaId);

        try {
            JsonNode node = objectMapper.readTree(json);
            script.setTitle(node.path("title").asText(topic));
            script.setHook(node.path("hook").asText(""));
            script.setScriptBody(node.path("script").asText(""));
            script.setShotList(node.path("shots").toString());
            script.setHashtags(node.path("hashtags").toString());
            script.setCaption(node.path("caption").asText(""));
            script.setMusicSuggestion(node.path("music").asText(""));
        } catch (Exception e) {
            log.warn("Failed to parse AI response, storing raw text: {}", e.getMessage());
            script.setTitle(topic);
            script.setScriptBody(raw);
            script.setShotList("[]");
            script.setHashtags("[]");
        }
        script.setStatus("DRAFT");
        return videoScriptRepository.save(script);
    }

    @Transactional(readOnly = true)
    public List<VideoScriptEntity> listByTenant(UUID tenantId) {
        return videoScriptRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Transactional
    public void delete(UUID tenantId, UUID id) {
        videoScriptRepository.findByIdAndTenantId(id, tenantId)
                .ifPresent(videoScriptRepository::delete);
    }

    private String buildPrompt(String topic, String platform, String type, String style,
                               int seconds, String templateCode) {
        return """
                You are an expert short-form video content creator. Generate a complete video script for the following brief.

                Topic: %s
                Platform: %s
                Content type: %s
                Style/tone: %s
                Target duration: %d seconds
                Blender template: %s

                This is a deterministic Blender render plan. For every shot:
                - action must be one of IDLE, TALK, WALK, RUN, WAVE, POINT, PRODUCT_TURN
                - camera must be one of CLOSE_UP, MEDIUM, WIDE, TRACKING, PRODUCT
                - environment and character are short reusable asset labels
                - durations must total approximately the target duration

                Return ONLY a valid JSON object (no markdown, no explanation) with exactly these fields:
                {
                  "title": "catchy video title (max 60 chars)",
                  "hook": "first 3-5 seconds hook line that stops the scroll",
                  "script": "full voiceover/dialogue script for the video",
                  "shots": [
                    {
                      "id": 1,
                      "duration": 3,
                      "visual": "describe what camera shows",
                      "audio": "voiceover or caption text",
                      "environment": "STUDIO",
                      "character": "PRESENTER",
                      "action": "TALK",
                      "expression": "HAPPY",
                      "camera": "MEDIUM"
                    },
                    ... (6-10 shots)
                  ],
                  "hashtags": ["#tag1", "#tag2", ... (10-15 relevant hashtags)],
                  "caption": "social media caption with hook + CTA (max 150 chars)",
                  "music": "suggested background music genre or mood"
                }
                """.formatted(topic, platform, type, style, seconds, templateCode);
    }

    private String buildFallback(String topic, String platform, int seconds) {
        return """
                {
                  "title": "%s",
                  "hook": "You won't believe this about %s...",
                  "script": "Hey everyone! Today we're talking about %s. Here's what you need to know: [Your key points here]. Follow for more content like this!",
                  "shots": [
                    {"id": 1, "duration": 3, "visual": "Presenter delivers the hook", "audio": "You won't believe this about %s...", "environment": "STUDIO", "character": "PRESENTER", "action": "TALK", "expression": "EXCITED", "camera": "CLOSE_UP"},
                    {"id": 2, "duration": 5, "visual": "Presenter walks toward the product", "audio": "Here's the key thing you need to know", "environment": "STUDIO", "character": "PRESENTER", "action": "WALK", "expression": "HAPPY", "camera": "TRACKING"},
                    {"id": 3, "duration": %d, "visual": "Product rotates while the presenter explains it", "audio": "Main script content goes here", "environment": "STUDIO", "character": "PRESENTER", "action": "PRODUCT_TURN", "expression": "NEUTRAL", "camera": "PRODUCT"},
                    {"id": 4, "duration": 3, "visual": "Presenter points to the call to action", "audio": "Follow for more tips like this!", "environment": "STUDIO", "character": "PRESENTER", "action": "POINT", "expression": "HAPPY", "camera": "MEDIUM"}
                  ],
                  "hashtags": ["#%s", "#trending", "#viral", "#howto", "#tips"],
                  "caption": "Everything you need to know about %s! 🔥 Follow for more. #%s",
                  "music": "Upbeat background music"
                }
                """.formatted(topic, topic, topic, topic, Math.max(seconds - 11, 10),
                topic.replaceAll("\\s+", ""), topic, topic.replaceAll("\\s+", ""));
    }

    private String stripMarkdown(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("(?s)^```\\w*\\n?", "").replaceAll("```$", "").trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }
}
