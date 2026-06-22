package com.whatsappbot.platform.core.dto;

import java.util.Map;

public record AnalyticsResult(
        long views,
        long likes,
        long comments,
        long shares,
        long clicks,
        long leads,
        Map<String, String> metadata
) {
    public static AnalyticsResult empty() {
        return new AnalyticsResult(0, 0, 0, 0, 0, 0, Map.of());
    }
}
