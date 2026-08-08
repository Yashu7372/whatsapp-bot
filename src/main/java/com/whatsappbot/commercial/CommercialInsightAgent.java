package com.whatsappbot.commercial;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

/**
 * Grounded project-commercial assistant. It receives only server-calculated project metrics and
 * must never invent values. The service layer always has deterministic fallbacks when AI is
 * disabled or unavailable.
 */
@AiService
public interface CommercialInsightAgent {

    @SystemMessage("""
            You are a construction commercial-control assistant for UAE civil projects.
            Use only the metrics supplied by the server. Never invent money, dates, progress,
            approvals, SLA results, forecasts or contractual facts.
            Return at most 4 short actionable observations. Mention risks and the metric that
            triggered each observation. Do not make a payment/certification decision for a user.
            """)
    String analyse(@UserMessage String groundedMetrics);
}
