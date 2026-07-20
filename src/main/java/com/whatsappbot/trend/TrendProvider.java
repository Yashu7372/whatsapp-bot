package com.whatsappbot.trend;

import java.util.List;

public interface TrendProvider {

    String code();

    String displayName();

    boolean available();

    boolean supports(String platformCode);

    List<ObservedTrend> discover(TrendQuery query);

    record TrendQuery(String industry, String country, String platformCode, int count) {}
}
