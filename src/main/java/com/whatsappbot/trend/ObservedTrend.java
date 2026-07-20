package com.whatsappbot.trend;

public record ObservedTrend(
        String keyword,
        String hashtag,
        String topic,
        double rawScore,
        String sourceName
) {}
