package com.whatsappbot.trend.provider;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.trends")
public class TrendProviderProperties {
    private String youtubeApiKey = "";
    private boolean googleRssEnabled = true;
}
