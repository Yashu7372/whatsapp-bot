package com.whatsappbot.lead;

import org.springframework.stereotype.Service;

@Service
public class LeadScoringService {

    public double scoreInboundMessage(String messageText) {
        if (messageText == null || messageText.isBlank()) return 0.1;
        String lower = messageText.toLowerCase();
        double score = 0.3;
        if (lower.contains("price") || lower.contains("cost") || lower.contains("how much")) score += 0.3;
        if (lower.contains("book") || lower.contains("appointment") || lower.contains("schedule")) score += 0.4;
        if (lower.contains("demo") || lower.contains("trial") || lower.contains("buy") || lower.contains("purchase")) score += 0.5;
        if (lower.contains("complaint") || lower.contains("problem") || lower.contains("issue")) score += 0.1;
        return Math.min(1.0, score);
    }

    public LeadIntentCategory detectIntent(String messageText) {
        if (messageText == null) return LeadIntentCategory.GENERAL_QUESTION;
        String lower = messageText.toLowerCase();
        if (lower.contains("price") || lower.contains("cost") || lower.contains("how much"))
            return LeadIntentCategory.PRICE_INQUIRY;
        if (lower.contains("book") || lower.contains("appointment") || lower.contains("schedule"))
            return LeadIntentCategory.BOOKING_REQUEST;
        if (lower.contains("demo") || lower.contains("trial"))
            return LeadIntentCategory.DEMO_REQUEST;
        if (lower.contains("complaint") || lower.contains("problem") || lower.contains("issue"))
            return LeadIntentCategory.COMPLAINT;
        if (lower.contains("support") || lower.contains("help"))
            return LeadIntentCategory.SUPPORT_REQUEST;
        if (lower.contains("service") || lower.contains("repair") || lower.contains("fix"))
            return LeadIntentCategory.SERVICE_REQUEST;
        return LeadIntentCategory.GENERAL_QUESTION;
    }
}
