package com.whatsappbot.platform.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.whatsappbot.platform.core.dto.AnalyticsRequest;
import com.whatsappbot.platform.core.dto.AnalyticsResult;
import com.whatsappbot.platform.core.dto.LeadCollectionRequest;
import com.whatsappbot.platform.core.dto.LeadSignalDto;
import com.whatsappbot.platform.core.dto.PublishCommand;
import com.whatsappbot.platform.core.dto.PublishResult;
import com.whatsappbot.platform.core.dto.TrendCollectionRequest;
import com.whatsappbot.platform.core.dto.TrendSignalDto;
import com.whatsappbot.platform.core.dto.WebhookParseResult;

import java.util.List;

public interface MarketingPlatformPlugin {

    String platformCode();

    PlatformCapabilities capabilities();

    List<TrendSignalDto> collectTrends(TrendCollectionRequest request);

    List<LeadSignalDto> collectLeadSignals(LeadCollectionRequest request);

    PublishResult publish(PublishCommand command);

    AnalyticsResult fetchAnalytics(AnalyticsRequest request);

    WebhookParseResult parseWebhook(JsonNode payload);
}
