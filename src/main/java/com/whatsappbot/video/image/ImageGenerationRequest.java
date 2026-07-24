package com.whatsappbot.video.image;

import java.util.List;

public record ImageGenerationRequest(
        String prompt,
        StoryboardQualityMode qualityMode,
        List<ImageReference> references
) {
}
