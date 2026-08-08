package com.whatsappbot.document.intake;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.upload-links")
public class UploadLinkProperties {
    private int defaultSessionTtlMinutes = 15;
    private int maxPasswordAttempts = 5;
    private int passwordLockoutMinutes = 15;
    /** Where the frontend's public /upload/:token page is served from, to build the shareable URL. */
    private String publicBaseUrl = "http://localhost:5173";
}
