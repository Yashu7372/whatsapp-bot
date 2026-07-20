package com.whatsappbot.reels;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.reels")
public class ReelProperties {
    private String rendererBaseUrl = "http://renderer:8090";
    private long pollIntervalMs = 2000;
    private int renderTimeoutSeconds = 240;
    private int maxAssetBytes = 10 * 1024 * 1024;
    private String defaultTemplate = "DYNAMIC_BOLD";
    private String defaultVoice = "af_heart";
}
