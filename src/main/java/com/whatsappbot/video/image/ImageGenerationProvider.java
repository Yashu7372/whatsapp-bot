package com.whatsappbot.video.image;

import java.math.BigDecimal;

public interface ImageGenerationProvider {
    String code();
    boolean available();
    BigDecimal estimateCost(StoryboardQualityMode qualityMode);
    GeneratedImage generate(ImageGenerationRequest request);
}
