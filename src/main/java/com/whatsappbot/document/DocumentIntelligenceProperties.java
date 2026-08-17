package com.whatsappbot.document;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "document.intelligence")
public class DocumentIntelligenceProperties {
    /** PDFs above this size are rejected rather than inlined as base64 into the multimodal prompt. */
    private long maxInlineBytes = 20L * 1024 * 1024;
}
