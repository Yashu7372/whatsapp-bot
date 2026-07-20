package com.whatsappbot.trend.provider;

import java.util.List;

public interface TrendProvider {
    String code();
    boolean available();
    List<TrendCandidate> discover(TrendQuery query);
}
