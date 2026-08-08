package com.whatsappbot.document.intake;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.document-intake")
public class DocumentIntakeProperties {
    /** Applies to every external channel — link, WhatsApp, and eventually email. */
    private long maxFileSizeBytes = 25L * 1024 * 1024;

    /** doc_type assigned to documents a customer sends over WhatsApp — always tenant-level, no project. */
    private String whatsappDocType = "WHATSAPP_INBOX";
}
