package com.whatsappbot.video.image;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class HybridImageProviderRouter {

    private final LocalImageGenerationProvider localProvider;
    private final GeminiImageGenerationProvider geminiProvider;

    public boolean available() {
        return localProvider.available() || geminiProvider.available();
    }

    public BigDecimal reservedCost(StoryboardQualityMode qualityMode) {
        return geminiProvider.available()
                ? geminiProvider.estimateCost(qualityMode)
                : BigDecimal.ZERO;
    }

    public GeneratedImage generate(ImageGenerationRequest request, BigDecimal maximumCostUsd) {
        if (localProvider.available()) {
            try {
                return localProvider.generate(request);
            } catch (Exception e) {
                log.warn("Local image generation failed; considering paid fallback: {}", e.getMessage());
            }
        }

        BigDecimal paidEstimate = geminiProvider.estimateCost(request.qualityMode());
        if (!geminiProvider.available()) {
            throw new IllegalStateException("No image provider is configured");
        }
        if (paidEstimate.compareTo(maximumCostUsd) > 0) {
            throw new IllegalStateException(
                    "Paid fallback estimate $" + paidEstimate + " exceeds shot cap $" + maximumCostUsd);
        }
        return geminiProvider.generate(request);
    }
}
