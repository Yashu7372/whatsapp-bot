package com.whatsappbot.trend;

import org.springframework.stereotype.Service;

@Service
public class TrendScoringService {

    public double computeFinalScore(double freshness, double growth, double relevance,
                                    double engagement, double brandSafety) {
        return freshness * 0.25 + growth * 0.25 + relevance * 0.25 + engagement * 0.15 + brandSafety * 0.10;
    }

    public void applyScores(TrendSignalEntity signal) {
        double raw = signal.getRawScore();
        double freshness = Math.min(1.0, raw);
        double growth = Math.min(1.0, raw * 0.9);
        double relevance = Math.min(1.0, raw * 0.8);
        double engagement = Math.min(1.0, raw * 0.7);
        double brandSafety = 0.9;
        signal.setFreshnessScore(freshness);
        signal.setGrowthScore(growth);
        signal.setRelevanceScore(relevance);
        signal.setEngagementScore(engagement);
        signal.setBrandSafetyScore(brandSafety);
        signal.setFinalScore(computeFinalScore(freshness, growth, relevance, engagement, brandSafety));
    }
}
