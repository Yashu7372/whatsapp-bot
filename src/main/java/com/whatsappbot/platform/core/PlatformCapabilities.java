package com.whatsappbot.platform.core;

import java.util.List;

public record PlatformCapabilities(
        boolean supportsWebhookInbound,
        boolean supportsOutboundMessaging,
        boolean supportsPublishing,
        boolean supportsScheduling,
        boolean supportsAnalytics,
        boolean supportsTrendCollection,
        boolean supportsLeadSignals,
        boolean supportsTemplates,
        List<String> supportedContentTypes
) {}
