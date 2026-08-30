package com.yashu.projectcontrol.intelligence;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Records committed commercial milestones as evidence-derived features. It deliberately
 * does not label ordinary certification/payment differences as anomalies without an
 * explicit project rule or validated detector.
 */
@Component
public class CommercialActivityIntelligenceCollector implements ProjectIntelligenceCollector {

    @Override
    public String code() {
        return "COMMERCIAL_ACTIVITY_COLLECTOR";
    }

    @Override
    public boolean supports(ProjectIntelligenceSignal signal) {
        return "PAYMENT_APPLICATION_SUBMITTED".equals(signal.triggerType())
                || "PAYMENT_APPLICATION_CERTIFIED".equals(signal.triggerType())
                || "PAYMENT_RECORDED".equals(signal.triggerType());
    }

    @Override
    public CollectorResult collect(ProjectIntelligenceSignal signal) {
        return new CollectorResult(
                List.of(new FeatureDraft(
                        signal.triggerType(),
                        "1",
                        signal.payloadJson(),
                        1.0d,
                        signal.occurredAt())),
                List.of());
    }
}
