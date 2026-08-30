package com.yashu.projectcontrol.intelligence;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * First live collector. It records that a controlled document revision now has a
 * trusted immutable evidence snapshot. The snapshot payload itself remains in the
 * evidence module; this derived feature stores only the compact source metadata
 * carried by the signal.
 */
@Component
public class DocumentEvidenceIntelligenceCollector implements ProjectIntelligenceCollector {

    static final String TRIGGER = "DOCUMENT_EVIDENCE_RECORDED";
    static final String FEATURE = "DOCUMENT_EVIDENCE_AVAILABLE";

    @Override
    public String code() {
        return "DOCUMENT_EVIDENCE_COLLECTOR";
    }

    @Override
    public boolean supports(ProjectIntelligenceSignal signal) {
        return TRIGGER.equals(signal.triggerType());
    }

    @Override
    public CollectorResult collect(ProjectIntelligenceSignal signal) {
        return new CollectorResult(
                List.of(new FeatureDraft(
                        FEATURE,
                        "1",
                        signal.payloadJson(),
                        1.0d,
                        signal.occurredAt())),
                List.of());
    }
}
