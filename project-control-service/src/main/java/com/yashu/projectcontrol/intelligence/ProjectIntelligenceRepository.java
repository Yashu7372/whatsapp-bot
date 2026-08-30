package com.yashu.projectcontrol.intelligence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class ProjectIntelligenceRepository {

    private final JdbcClient jdbc;

    ProjectIntelligenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    UUID enqueue(ProjectIntelligenceSignal signal, String collectorCode) {
        Optional<UUID> existing = findJobId(signal.projectId(), collectorCode, signal.triggerKey());
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        try {
            jdbc.sql("""
                    INSERT INTO project_intelligence_collector_jobs (
                        id, project_id, scope_id, collector_code, trigger_type, subject_type,
                        subject_id, trigger_key, payload_json, occurred_at, status, attempt_count,
                        created_at, updated_at)
                    VALUES (
                        :id, :projectId, :scopeId, :collectorCode, :triggerType, :subjectType,
                        :subjectId, :triggerKey, :payloadJson, :occurredAt, 'PENDING', 0,
                        :createdAt, :updatedAt)
                    """)
                    .param("id", id)
                    .param("projectId", signal.projectId())
                    .param("scopeId", signal.scopeId())
                    .param("collectorCode", collectorCode)
                    .param("triggerType", signal.triggerType())
                    .param("subjectType", signal.subjectType())
                    .param("subjectId", signal.subjectId())
                    .param("triggerKey", signal.triggerKey())
                    .param("payloadJson", signal.payloadJson())
                    .param("occurredAt", signal.occurredAt())
                    .param("createdAt", now)
                    .param("updatedAt", now)
                    .update();
            return id;
        } catch (DuplicateKeyException ex) {
            return findJobId(signal.projectId(), collectorCode, signal.triggerKey())
                    .orElseThrow(() -> ex);
        }
    }

    private Optional<UUID> findJobId(UUID projectId, String collectorCode, String triggerKey) {
        return jdbc.sql("""
                        SELECT id
                        FROM project_intelligence_collector_jobs
                        WHERE project_id = :projectId
                          AND collector_code = :collectorCode
                          AND trigger_key = :triggerKey
                        """)
                .param("projectId", projectId)
                .param("collectorCode", collectorCode)
                .param("triggerKey", triggerKey)
                .query(UUID.class)
                .optional();
    }

    Optional<Job> findJob(UUID id) {
        return jdbc.sql("""
                        SELECT id, project_id, scope_id, collector_code, trigger_type, subject_type,
                               subject_id, trigger_key, payload_json, occurred_at, status, attempt_count,
                               claimed_at, completed_at, last_error
                        FROM project_intelligence_collector_jobs
                        WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, rowNum) -> new Job(
                        rs.getObject("id", UUID.class),
                        rs.getObject("project_id", UUID.class),
                        rs.getObject("scope_id", UUID.class),
                        rs.getString("collector_code"),
                        rs.getString("trigger_type"),
                        rs.getString("subject_type"),
                        rs.getObject("subject_id", UUID.class),
                        rs.getString("trigger_key"),
                        rs.getString("payload_json"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("status"),
                        rs.getInt("attempt_count"),
                        rs.getTimestamp("claimed_at") == null ? null : rs.getTimestamp("claimed_at").toInstant(),
                        rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                        rs.getString("last_error")))
                .optional();
    }

    boolean claim(UUID id) {
        return jdbc.sql("""
                        UPDATE project_intelligence_collector_jobs
                        SET status = 'RUNNING',
                            attempt_count = attempt_count + 1,
                            claimed_at = CURRENT_TIMESTAMP,
                            updated_at = CURRENT_TIMESTAMP,
                            last_error = NULL
                        WHERE id = :id
                          AND status IN ('PENDING', 'RETRY')
                          AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP)
                        """)
                .param("id", id)
                .update() == 1;
    }

    void complete(UUID id) {
        jdbc.sql("""
                        UPDATE project_intelligence_collector_jobs
                        SET status = 'SUCCEEDED',
                            completed_at = CURRENT_TIMESTAMP,
                            next_attempt_at = NULL,
                            updated_at = CURRENT_TIMESTAMP,
                            last_error = NULL
                        WHERE id = :id AND status = 'RUNNING'
                        """)
                .param("id", id)
                .update();
    }

    void retry(UUID id, Instant nextAttemptAt, String error) {
        jdbc.sql("""
                        UPDATE project_intelligence_collector_jobs
                        SET status = 'RETRY',
                            next_attempt_at = :nextAttemptAt,
                            claimed_at = NULL,
                            updated_at = CURRENT_TIMESTAMP,
                            last_error = :lastError
                        WHERE id = :id AND status = 'RUNNING'
                        """)
                .param("id", id)
                .param("nextAttemptAt", nextAttemptAt)
                .param("lastError", truncate(error, 2000))
                .update();
    }

    void failFinal(UUID id, String error) {
        jdbc.sql("""
                        UPDATE project_intelligence_collector_jobs
                        SET status = 'FAILED_FINAL',
                            next_attempt_at = NULL,
                            claimed_at = NULL,
                            completed_at = CURRENT_TIMESTAMP,
                            updated_at = CURRENT_TIMESTAMP,
                            last_error = :lastError
                        WHERE id = :id AND status = 'RUNNING'
                        """)
                .param("id", id)
                .param("lastError", truncate(error, 2000))
                .update();
    }

    int recoverStale(Instant staleBefore) {
        return jdbc.sql("""
                        UPDATE project_intelligence_collector_jobs
                        SET status = 'RETRY',
                            next_attempt_at = CURRENT_TIMESTAMP,
                            claimed_at = NULL,
                            updated_at = CURRENT_TIMESTAMP,
                            last_error = 'Recovered stale RUNNING collector job'
                        WHERE status = 'RUNNING'
                          AND claimed_at < :staleBefore
                        """)
                .param("staleBefore", staleBefore)
                .update();
    }

    List<UUID> findDueJobIds(int limit) {
        return jdbc.sql("""
                        SELECT id
                        FROM project_intelligence_collector_jobs
                        WHERE status = 'PENDING'
                           OR (status = 'RETRY' AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP))
                        ORDER BY created_at ASC
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    void saveFeature(
            UUID jobId,
            ProjectIntelligenceSignal signal,
            ProjectIntelligenceCollector.FeatureDraft feature) {
        Integer existing = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM project_intelligence_feature_snapshots
                        WHERE source_job_id = :jobId AND feature_code = :featureCode
                        """)
                .param("jobId", jobId)
                .param("featureCode", feature.featureCode())
                .query(Integer.class)
                .single();
        if (existing != null && existing > 0) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO project_intelligence_feature_snapshots (
                            id, project_id, scope_id, subject_type, subject_id,
                            feature_code, feature_version, value_json, confidence, observed_at,
                            source_trigger_type, source_job_id, created_at)
                        VALUES (
                            :id, :projectId, :scopeId, :subjectType, :subjectId,
                            :featureCode, :featureVersion, :valueJson, :confidence, :observedAt,
                            :triggerType, :jobId, :createdAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("projectId", signal.projectId())
                .param("scopeId", signal.scopeId())
                .param("subjectType", signal.subjectType())
                .param("subjectId", signal.subjectId())
                .param("featureCode", feature.featureCode())
                .param("featureVersion", feature.featureVersion())
                .param("valueJson", feature.valueJson())
                .param("confidence", BigDecimal.valueOf(feature.confidence()))
                .param("observedAt", feature.observedAt() == null ? signal.occurredAt() : feature.observedAt())
                .param("triggerType", signal.triggerType())
                .param("jobId", jobId)
                .param("createdAt", Instant.now())
                .update();
    }

    UUID saveFinding(
            UUID jobId,
            ProjectIntelligenceSignal signal,
            ProjectIntelligenceCollector.FindingDraft finding) {
        Optional<UUID> existing = jdbc.sql("""
                        SELECT id
                        FROM project_intelligence_findings
                        WHERE source_job_id = :jobId AND finding_code = :findingCode
                        """)
                .param("jobId", jobId)
                .param("findingCode", finding.findingCode())
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO project_intelligence_findings (
                            id, project_id, scope_id, subject_type, subject_id,
                            finding_code, severity, finding_json, method_code, method_version,
                            confidence, source_job_id, created_at)
                        VALUES (
                            :id, :projectId, :scopeId, :subjectType, :subjectId,
                            :findingCode, :severity, :findingJson, :methodCode, :methodVersion,
                            :confidence, :jobId, :createdAt)
                        """)
                .param("id", id)
                .param("projectId", signal.projectId())
                .param("scopeId", signal.scopeId())
                .param("subjectType", signal.subjectType())
                .param("subjectId", signal.subjectId())
                .param("findingCode", finding.findingCode())
                .param("severity", finding.severity())
                .param("findingJson", finding.findingJson())
                .param("methodCode", finding.methodCode())
                .param("methodVersion", finding.methodVersion())
                .param("confidence", BigDecimal.valueOf(finding.confidence()))
                .param("jobId", jobId)
                .param("createdAt", Instant.now())
                .update();
        return id;
    }

    long countJobs(UUID projectId, String triggerKey) {
        Long count = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM project_intelligence_collector_jobs
                        WHERE project_id = :projectId AND trigger_key = :triggerKey
                        """)
                .param("projectId", projectId)
                .param("triggerKey", triggerKey)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    long countFeatures(UUID projectId, String featureCode) {
        Long count = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM project_intelligence_feature_snapshots
                        WHERE project_id = :projectId AND feature_code = :featureCode
                        """)
                .param("projectId", projectId)
                .param("featureCode", featureCode)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return "Collector failed without an error message";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    record Job(
            UUID id,
            UUID projectId,
            UUID scopeId,
            String collectorCode,
            String triggerType,
            String subjectType,
            UUID subjectId,
            String triggerKey,
            String payloadJson,
            Instant occurredAt,
            String status,
            int attemptCount,
            Instant claimedAt,
            Instant completedAt,
            String lastError) {

        ProjectIntelligenceSignal signal() {
            return new ProjectIntelligenceSignal(
                    projectId, scopeId, triggerType, subjectType, subjectId, triggerKey, payloadJson, occurredAt);
        }
    }
}
