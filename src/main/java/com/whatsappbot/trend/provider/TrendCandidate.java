package com.whatsappbot.trend.provider;

import com.whatsappbot.trend.TrendSourceType;

public record TrendCandidate(
        String keyword,
        String hashtag,
        String topic,
        double rawScore,
        String sourceName,
        TrendSourceType sourceType
) {}
