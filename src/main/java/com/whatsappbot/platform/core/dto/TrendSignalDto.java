package com.whatsappbot.platform.core.dto;

import java.util.Map;

public record TrendSignalDto(
        String keyword,
        String hashtag,
        String topic,
        String country,
        String industry,
        double rawScore,
        Map<String, String> metadata
) {}
