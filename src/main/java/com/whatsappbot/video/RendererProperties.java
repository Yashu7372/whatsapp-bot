package com.whatsappbot.video;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.video.renderer")
public class RendererProperties {
    private boolean enabled = true;
    private String baseUrl = "http://localhost:8090";
    private String outputDir = "./local-renders";
    private int pollIntervalMs = 2000;
    private int batchSize = 2;
    private int maxRetries = 3;
    private int stuckRecoveryMinutes = 15;
}
