package com.yashu.projectcontrol.intelligence;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Captures committed workflow activity as a compact derived snapshot. It does not
 * determine workflow authority or completion; WorkflowService remains source of truth.
 */
@Component
public class WorkflowActivityIntelligenceCollector implements ProjectIntelligenceCollector {

    @Override
    public String code() {
        return "WORKFLOW_ACTIVITY_COLLECTOR";
    }

    @Override
    public boolean supports(ProjectIntelligenceSignal signal) {
        return "WORKFLOW_STARTED".equals(signal.triggerType())
                || "WORKFLOW_ACTION_RECORDED".equals(signal.triggerType());
    }

    @Override
    public CollectorResult collect(ProjectIntelligenceSignal signal) {
        return new CollectorResult(
                List.of(new FeatureDraft(
                        "WORKFLOW_STATE_OBSERVED",
                        "1",
                        signal.payloadJson(),
                        1.0d,
                        signal.occurredAt())),
                List.of());
    }
}
