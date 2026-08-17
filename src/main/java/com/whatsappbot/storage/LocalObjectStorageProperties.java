package com.whatsappbot.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.storage")
public class LocalObjectStorageProperties {
    /** Base URL the local-dev upload/download links are built against. */
    private String localBaseUrl = "http://localhost:8080";

    /** How long a signed local upload/download URL remains valid. */
    private long uploadTokenTtlSeconds = 900;
}
