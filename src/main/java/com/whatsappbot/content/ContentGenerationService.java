package com.whatsappbot.content;

import com.whatsappbot.domain.tenant.TenantEntity;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
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

        String prompt = String.format(
                "Generate a %s content for %s about: %s. Include 3-5 hashtags. " +
                "Format: body text first, then hashtags on a new line starting with #. " +
                "Keep it engaging and platform-appropriate.",
                contentType, platformCode, topic
        );

        String aiResponse;
        try {
            aiResponse = chatModel.chat(prompt);
        } catch (Exception e) {
            log.warn("AI content generation failed for idea={}, using fallback. error={}", idea.getId(), e.getMessage());
            aiResponse = "Exciting news about " + topic + "!\n#trending #content #marketing";
        }

        // Parse AI response: split body and hashtags
        String body;
        String[] hashtags;
        int lastHashNewline = aiResponse.lastIndexOf("\n#");
        if (lastHashNewline >= 0) {
            body = aiResponse.substring(0, lastHashNewline).trim();
            String hashtagLine = aiResponse.substring(lastHashNewline).trim();
            hashtags = Arrays.stream(hashtagLine.split("\\s+"))
                    .filter(s -> s.startsWith("#"))
                    .toArray(String[]::new);
        } else {
            body = aiResponse.trim();
            hashtags = new String[0];
        }

        ContentVariantEntity variant = ContentVariantEntity.create(tenant, idea.getId(), body, hashtags, null, 1);
        contentVariantRepository.save(variant);

        return idea;
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
}
