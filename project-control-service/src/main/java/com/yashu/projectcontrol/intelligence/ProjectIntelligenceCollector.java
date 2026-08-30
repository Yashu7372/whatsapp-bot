package com.yashu.projectcontrol.intelligence;

import java.time.Instant;
import java.util.List;

/**
 * Bounded collector contract. A collector derives project intelligence from one
 * committed signal; it does not mutate authoritative workflow, financial,
 * verification or document state.
 */
public interface ProjectIntelligenceCollector {

    String code();

    boolean supports(ProjectIntelligenceSignal signal);

    CollectorResult collect(ProjectIntelligenceSignal signal);

    record CollectorResult(List<FeatureDraft> features, List<FindingDraft> findings) {
        public CollectorResult {
            features = features == null ? List.of() : List.copyOf(features);
            findings = findings == null ? List.of() : List.copyOf(findings);
        }

        public static CollectorResult empty() {
            return new CollectorResult(List.of(), List.of());
        }
    }

    record FeatureDraft(
            String featureCode,
            String featureVersion,
            String valueJson,
            double confidence,
            Instant observedAt) {
    }

    record FindingDraft(
            String findingCode,
            String severity,
            String findingJson,
            String methodCode,
            String methodVersion,
            double confidence) {
    }
}
