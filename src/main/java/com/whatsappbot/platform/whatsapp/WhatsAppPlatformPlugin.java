package com.whatsappbot.platform.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.whatsappbot.platform.core.MarketingPlatformPlugin;
import com.whatsappbot.platform.core.PlatformCapabilities;
import com.whatsappbot.platform.core.PlatformCode;
import com.whatsappbot.platform.core.dto.AnalyticsRequest;
import com.whatsappbot.platform.core.dto.AnalyticsResult;
import com.whatsappbot.platform.core.dto.LeadCollectionRequest;
import com.whatsappbot.platform.core.dto.LeadSignalDto;
import com.whatsappbot.platform.core.dto.PublishCommand;
import com.whatsappbot.platform.core.dto.PublishResult;
import com.whatsappbot.platform.core.dto.TrendCollectionRequest;
import com.whatsappbot.platform.core.dto.TrendSignalDto;
import com.whatsappbot.platform.core.dto.WebhookParseResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * WhatsApp plugin wrapper. Existing webhook parsing and outbound sending
 * continue to live in WebhookApplicationService and WhatsAppGraphClient —
 * this class registers WhatsApp in the plugin registry without duplicating
 * any existing logic.
 */
@Component
public class WhatsAppPlatformPlugin implements MarketingPlatformPlugin {

    @Override
    public String platformCode() {
        return PlatformCode.WHATSAPP.name();
    }

    @Override
    public PlatformCapabilities capabilities() {
        return WhatsAppPlatformCapabilities.INSTANCE;
    }

    @Override
    public List<TrendSignalDto> collectTrends(TrendCollectionRequest request) {
        // WhatsApp does not expose trend data through its API.
        return List.of();
    }

    @Override
    public List<LeadSignalDto> collectLeadSignals(LeadCollectionRequest request) {
        // Lead signals come through inbound webhook messages, not a pull API.
        return List.of();
    }

    @Override
    public PublishResult publish(PublishCommand command) {
        // WhatsApp outbound goes through WhatsAppGraphClient, not a generic publish flow.
        throw new UnsupportedOperationException(
                "WhatsApp outbound messages must go through WhatsAppGraphClient. " +
                "Use the template or live-chat send paths instead.");
    }

    @Override
    public AnalyticsResult fetchAnalytics(AnalyticsRequest request) {
        return AnalyticsResult.empty();
    }

    @Override
    public WebhookParseResult parseWebhook(JsonNode payload) {
        // Parsing is owned by WebhookApplicationService + WhatsAppWebhookParser.
        return WebhookParseResult.notHandled();
    }
}
