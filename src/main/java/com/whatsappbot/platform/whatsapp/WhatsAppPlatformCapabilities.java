package com.whatsappbot.platform.whatsapp;

import com.whatsappbot.platform.core.PlatformCapabilities;

import java.util.List;

public final class WhatsAppPlatformCapabilities {

    public static final PlatformCapabilities INSTANCE = new PlatformCapabilities(
            true,   // supportsWebhookInbound
            true,   // supportsOutboundMessaging
            false,  // supportsPublishing
            false,  // supportsScheduling
            false,  // supportsAnalytics
            false,  // supportsTrendCollection
            true,   // supportsLeadSignals
            true,   // supportsTemplates
            List.of("TEXT", "IMAGE", "TEMPLATE", "INTERACTIVE")
    );

    private WhatsAppPlatformCapabilities() {}
}
