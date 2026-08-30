package com.yashu.projectcontrol.intelligence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Small Project-Control-specific execution coordinator inspired by the Engineering
 * Control Plane model: signal -> durable collector job -> evidence-derived feature /
 * finding. It never scans whole projects and never mutates authoritative domain state.
 */
@Service
public class ProjectIntelligenceCoordinator {

    private static final List<String> SEVERITIES = List.of("INFO", "ATTENTION", "HIGH", "CRITICAL");

    private final ProjectIntelligenceRepository repository;
    private final Map<String, ProjectIntelligenceCollector> collectors;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final int maxAttempts;
    private final int recoveryBatchSize;
    private final Duration staleAfter;
    private final Duration retryBaseDelay;

    public ProjectIntelligenceCoordinator(
            ProjectIntelligenceRepository repository,
            List<ProjectIntelligenceCollector> collectors,
            ObjectMapper objectMapper,
            ApplicationEventPublisher events,
            @Value("${project-control.intelligence.max-attempts:5}") int maxAttempts,
            @Value("${project-control.intelligence.recovery-batch-size:20}") int recoveryBatchSize,
            @Value("${project-control.intelligence.stale-after-seconds:300}") long staleAfterSeconds,
            @Value("${project-control.intelligence.retry-base-seconds:30}") long retryBaseSeconds) {
        this.repository = repository;
        this.collectors = collectorMap(collectors);
        this.objectMapper = objectMapper;
        this.events = events;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.recoveryBatchSize = Math.max(1, recoveryBatchSize);
        this.staleAfter = Duration.ofSeconds(Math.max(30, staleAfterSeconds));
        this.retryBaseDelay = Duration.ofSeconds(Math.max(1, retryBaseSeconds));
    }

    public void accept(ProjectIntelligenceSignal signal) {
        validateJsonObject(signal.payloadJson(), "signal payloadJson");
        for (ProjectIntelligenceCollector collector : collectors.values()) {
            if (!collector.supports(signal)) {
                continue;
            }
            UUID jobId = repository.enqueue(signal, collector.code());
            process(jobId);
        }
    }

    public void process(UUID jobId) {
        ProjectIntelligenceRepository.Job beforeClaim = repository.findJob(jobId).orElse(null);
        if (beforeClaim == null || !repository.claim(jobId)) {
            return;
        }
        int currentAttempt = beforeClaim.attemptCount() + 1;
        try {
            ProjectIntelligenceCollector collector = collectors.get(beforeClaim.collectorCode());
            if (collector == null) {
                throw new IllegalStateException("Collector is not registered: " + beforeClaim.collectorCode());
            }
            ProjectIntelligenceSignal signal = beforeClaim.signal();
            ProjectIntelligenceCollector.CollectorResult result = collector.collect(signal);
            if (result == null) {
                result = ProjectIntelligenceCollector.CollectorResult.empty();
            }
            for (ProjectIntelligenceCollector.FeatureDraft feature : result.features()) {
                validateFeature(feature);
                repository.saveFeature(jobId, signal, feature);
            }
            for (ProjectIntelligenceCollector.FindingDraft finding : result.findings()) {
                validateFinding(finding);
                UUID findingId = repository.saveFinding(jobId, signal, finding);
                events.publishEvent(new ProjectIntelligenceFindingRaised(
                        findingId,
                        signal.projectId(),
                        signal.scopeId(),
                        signal.subjectType(),
                        signal.subjectId(),
                        normalizeCode(finding.findingCode(), "findingCode"),
                        normalizeCode(finding.severity(), "severity"),
                        finding.confidence(),
                        Instant.now()));
            }
            repository.complete(jobId);
        } catch (RuntimeException ex) {
            if (currentAttempt >= maxAttempts) {
                repository.failFinal(jobId, errorMessage(ex));
            } else {
                long multiplier = Math.min(16L, 1L << Math.min(4, Math.max(0, currentAttempt - 1)));
                repository.retry(jobId, Instant.now().plus(retryBaseDelay.multipliedBy(multiplier)), errorMessage(ex));
            }
        }
    }

    /**
     * Reliability only. This scans the small indexed collector-job table, not project data.
     */
    @Scheduled(
            fixedDelayString = "${project-control.intelligence.recovery-scan-ms:60000}",
            initialDelayString = "${project-control.intelligence.recovery-initial-delay-ms:60000}")
    public void recoverIncompleteJobs() {
        repository.recoverStale(Instant.now().minus(staleAfter));
        for (UUID jobId : repository.findDueJobIds(recoveryBatchSize)) {
            process(jobId);
        }
    }

    private void validateFeature(ProjectIntelligenceCollector.FeatureDraft feature) {
        if (feature == null) {
            throw new IllegalArgumentException("Collector returned a null feature");
        }
        normalizeCode(feature.featureCode(), "featureCode");
        requireText(feature.featureVersion(), "featureVersion");
        validateConfidence(feature.confidence(), "feature confidence");
        validateJsonObject(feature.valueJson(), "feature valueJson");
    }

    private void validateFinding(ProjectIntelligenceCollector.FindingDraft finding) {
        if (finding == null) {
            throw new IllegalArgumentException("Collector returned a null finding");
        }
        normalizeCode(finding.findingCode(), "findingCode");
        String severity = normalizeCode(finding.severity(), "severity");
        if (!SEVERITIES.contains(severity)) {
            throw new IllegalArgumentException("Unsupported finding severity: " + severity);
        }
        requireText(finding.methodCode(), "methodCode");
        requireText(finding.methodVersion(), "methodVersion");
        validateConfidence(finding.confidence(), "finding confidence");
        validateJsonObject(finding.findingJson(), "findingJson");
    }

    private void validateJsonObject(String json, String field) {
        try {
            JsonNode root = objectMapper.readTree(requireText(json, field));
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException(field + " must be a JSON object");
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException(field + " must be valid JSON", ex);
        }
    }

    private static void validateConfidence(double confidence, String field) {
        if (!Double.isFinite(confidence) || confidence < 0.0d || confidence > 1.0d) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
    }

    private static Map<String, ProjectIntelligenceCollector> collectorMap(
            List<ProjectIntelligenceCollector> collectors) {
        Map<String, ProjectIntelligenceCollector> result = new LinkedHashMap<>();
        for (ProjectIntelligenceCollector collector : collectors) {
            String code = normalizeCode(collector.code(), "collector code");
            if (result.putIfAbsent(code, collector) != null) {
                throw new IllegalStateException("Duplicate Project Intelligence collector code: " + code);
            }
        }
        return Map.copyOf(result);
    }

    private static String normalizeCode(String value, String field) {
        return requireText(value, field).toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String errorMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
