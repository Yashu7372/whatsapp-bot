package com.whatsappbot.video.engine.adapter;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import com.whatsappbot.video.VideoScriptEntity;
import com.whatsappbot.video.VideoScriptService;
import com.whatsappbot.video.engine.GenerationAdapter;
import com.whatsappbot.video.engine.GenerationArtifact;
import com.whatsappbot.video.engine.GenerationArtifactType;
import com.whatsappbot.video.engine.GenerationCapability;
import com.whatsappbot.video.engine.GenerationContext;
import com.whatsappbot.video.engine.StageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reuses the existing LangChain4j-backed script service without coupling the
 * engine to a specific LLM provider.
 */
@Component
@RequiredArgsConstructor
public class VideoScriptGenerationAdapter implements GenerationAdapter {

    private final VideoScriptService videoScriptService;
    private final TenantRepository tenantRepository;

    @Override
    public GenerationCapability capability() {
        return GenerationCapability.CONTENT;
    }

    @Override
    public String name() {
        return "video-script";
    }

    @Override
    public StageResult generate(GenerationContext context) {
        TenantEntity tenant = tenantRepository.findById(context.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + context.tenantId()));

        VideoScriptEntity script = videoScriptService.generate(
                tenant,
                context.topic(),
                context.platform(),
                context.option("contentType", "REEL"),
                context.option("style", "ENGAGING"),
                context.targetDurationSeconds(),
                uuidOption(context.options(), "contentIdeaId")
        );

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("scriptId", script.getId().toString());
        put(metadata, "title", script.getTitle());
        put(metadata, "hook", script.getHook());
        put(metadata, "shotList", script.getShotList());
        put(metadata, "caption", script.getCaption());
        put(metadata, "hashtags", script.getHashtags());
        put(metadata, "music", script.getMusicSuggestion());

        GenerationArtifact artifact = new GenerationArtifact(
                GenerationArtifactType.SCRIPT,
                script.getScriptBody(),
                "configured-chat-model",
                metadata
        );
        return StageResult.of(artifact, "Script generated and ready for content gate validation.");
    }

    private UUID uuidOption(Map<String, String> options, String key) {
        String raw = options.get(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void put(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
