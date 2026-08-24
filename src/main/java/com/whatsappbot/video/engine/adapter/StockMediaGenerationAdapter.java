package com.whatsappbot.video.engine.adapter;

import com.whatsappbot.stock.StockMediaService;
import com.whatsappbot.video.engine.GenerationAdapter;
import com.whatsappbot.video.engine.GenerationArtifact;
import com.whatsappbot.video.engine.GenerationArtifactType;
import com.whatsappbot.video.engine.GenerationCapability;
import com.whatsappbot.video.engine.GenerationContext;
import com.whatsappbot.video.engine.StageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Optional stock-media adapter. If no provider is configured, supports() is
 * false and the pipeline continues without B-roll.
 */
@Component
@RequiredArgsConstructor
public class StockMediaGenerationAdapter implements GenerationAdapter {

    private final StockMediaService stockMediaService;

    @Override
    public GenerationCapability capability() {
        return GenerationCapability.VISUAL_ASSETS;
    }

    @Override
    public String name() {
        return "stock-media";
    }

    @Override
    public boolean supports(GenerationContext context) {
        StockMediaService.Capabilities capabilities = stockMediaService.capabilities();
        return capabilities.pexels() || capabilities.pixabay();
    }

    @Override
    public StageResult generate(GenerationContext context) {
        int requested = parseCount(context.option("stockAssetCount", "4"));
        StockMediaService.SearchResult result = stockMediaService.search(
                context.option("stockQuery", context.topic()),
                context.option("stockProvider", "AUTO"),
                1,
                requested
        );

        List<GenerationArtifact> artifacts = result.items().stream()
                .map(video -> new GenerationArtifact(
                        GenerationArtifactType.BROLL,
                        video.downloadUrl(),
                        video.provider(),
                        Map.of(
                                "providerId", video.providerId(),
                                "sourcePageUrl", video.sourcePageUrl(),
                                "creatorName", video.creatorName(),
                                "durationSeconds", Integer.toString(video.durationSeconds())
                        )
                ))
                .toList();

        String message = artifacts.isEmpty()
                ? "No optional stock media matched; renderer will use the normal template path."
                : "Selected " + artifacts.size() + " optional stock media assets.";
        return new StageResult(artifacts, message);
    }

    private int parseCount(String raw) {
        try {
            return Math.max(1, Math.min(Integer.parseInt(raw), 8));
        } catch (NumberFormatException e) {
            return 4;
        }
    }
}
