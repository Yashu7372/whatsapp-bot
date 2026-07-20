package com.whatsappbot.trend.provider;

public record TrendQuery(
        String industry,
        String country,
        String platformCode,
        int count
) {}
