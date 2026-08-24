package com.whatsappbot.content;

import com.whatsappbot.domain.tenant.TenantEntity;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class ContentGenerationService {

    private final ContentIdeaRepository contentIdeaRepository;
    private final ContentVariantRepository contentVariantRepository;
    private final ChatModel chatModel;

    public ContentGenerationService(ContentIdeaRepository contentIdeaRepository,
                                    ContentVariantRepository contentVariantRepository,
                                    ChatModel chatModel) {
        this.contentIdeaRepository = contentIdeaRepository;
        this.contentVariantRepository = contentVariantRepository;
        this.chatModel = chatModel;
    }

    @Transactional
    public ContentIdeaEntity generateIdea(TenantEntity tenant, UUID campaignId, String platformCode,
                                          String contentType, String topic) {
        ContentType type = ContentType.valueOf(contentType.toUpperCase());
        ContentIdeaEntity idea = ContentIdeaEntity.create(tenant, campaignId, platformCode, type, topic);
        idea = contentIdeaRepository.save(idea);

        String prompt = buildPrompt(type, platformCode, topic);
        String aiResponse = generate(type, idea.getId(), prompt, topic);
        ParsedContent parsed = parseResponse(type, aiResponse);

        ContentVariantEntity variant = ContentVariantEntity.create(
                tenant, idea.getId(), parsed.body(), parsed.hashtags(), null, 1);
        contentVariantRepository.save(variant);

        return idea;
    }

    private String buildPrompt(ContentType type, String platformCode, String topic) {
        if (type == ContentType.ARTICLE) {
            return """
                    You are generating source-grounded engineering portfolio content.
                    Follow the supplied instruction exactly. The instruction may represent one section rather than a full article.
                    Do not add hashtags, social-media filler, marketing language, or unrelated calls to action.
                    Do not invent production incidents, implementation facts, metrics, files, commits, tests, or outcomes.
                    Treat VERIFIED as evidence-backed, DESIGN_INTENT as intended architecture, and UNKNOWN as unresolved.
                    When an incident is labelled generalized or hypothetical, keep that label explicit.
                    Return only the requested article prose.

                    Requested generation instruction:
                    %s
                    """.formatted(topic);
        }

        return String.format(
                "Generate a %s content for %s about: %s. Include 3-5 hashtags. " +
                        "Format: body text first, then hashtags on a new line starting with #. " +
                        "Keep it engaging and platform-appropriate.",
                type.name(), platformCode, topic
        );
    }

    private String generate(ContentType type, UUID ideaId, String prompt, String topic) {
        try {
            return chatModel.chat(prompt);
        } catch (Exception e) {
            if (type == ContentType.ARTICLE) {
                log.error("Article generation failed for idea={}. error={}", ideaId, e.getMessage());
                throw new IllegalStateException("Article generation failed", e);
            }

            log.warn("AI content generation failed for idea={}, using social-content fallback. error={}",
                    ideaId, e.getMessage());
            return "Exciting news about " + topic + "!\n#trending #content #marketing";
        }
    }

    private ParsedContent parseResponse(ContentType type, String aiResponse) {
        if (type == ContentType.ARTICLE) {
            return new ParsedContent(aiResponse.trim(), new String[0]);
        }

        int lastHashNewline = aiResponse.lastIndexOf("\n#");
        if (lastHashNewline < 0) {
            return new ParsedContent(aiResponse.trim(), new String[0]);
        }

        String body = aiResponse.substring(0, lastHashNewline).trim();
        String hashtagLine = aiResponse.substring(lastHashNewline).trim();
        String[] hashtags = Arrays.stream(hashtagLine.split("\\s+"))
                .filter(s -> s.startsWith("#"))
                .toArray(String[]::new);
        return new ParsedContent(body, hashtags);
    }

    @Transactional(readOnly = true)
    public List<ContentIdeaEntity> listIdeas(UUID tenantId, UUID campaignId) {
        if (campaignId == null) {
            return contentIdeaRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
        }
        return contentIdeaRepository.findAllByTenantIdAndCampaignIdOrderByCreatedAtDesc(tenantId, campaignId);
    }

    @Transactional
    public ContentIdeaEntity updateStatus(UUID ideaId, ContentStatus status) {
        ContentIdeaEntity idea = contentIdeaRepository.findById(ideaId)
                .orElseThrow(() -> new IllegalArgumentException("ContentIdea not found: " + ideaId));
        idea.setStatus(status);
        return contentIdeaRepository.save(idea);
    }

    private record ParsedContent(String body, String[] hashtags) {}
}
