package com.whatsappbot.video.image;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.media-generation")
public class MediaGenerationProperties {

    private BigDecimal defaultReelBudgetUsd = new BigDecimal("1.00");
    private BigDecimal defaultShotBudgetUsd = new BigDecimal("0.20");
    private BigDecimal hardMaximumReelBudgetUsd = new BigDecimal("5.00");
    private BigDecimal hardMaximumShotBudgetUsd = new BigDecimal("0.50");
    private Local local = new Local();
    private Gemini gemini = new Gemini();

    @Getter
    @Setter
    public static class Local {
        private boolean enabled = false;
        private String baseUrl = "http://localhost:8188";
        private Duration timeout = Duration.ofMinutes(5);
    }

    @Getter
    @Setter
    public static class Gemini {
        private boolean enabled = false;
        private String apiKey = "";
        private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";
        private String flashModel = "gemini-3.1-flash-image";
        private String proModel = "gemini-3-pro-image";
        private Duration timeout = Duration.ofMinutes(3);
        private BigDecimal flashEstimatedCostUsd = new BigDecimal("0.0800");
        private BigDecimal proEstimatedCostUsd = new BigDecimal("0.1600");
    }
}
