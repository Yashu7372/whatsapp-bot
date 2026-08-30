package com.yashu.projectcontrol;

import com.yashu.projectcontrol.intelligence.ProjectIntelligenceCoordinator;
import com.yashu.projectcontrol.intelligence.ProjectIntelligenceSignal;
import com.yashu.projectcontrol.project.ProjectService;
import com.yashu.projectcontrol.scope.ScopeService;
import com.yashu.projectcontrol.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = "project-control.intelligence.recovery-initial-delay-ms=3600000")
@ActiveProfiles("test")
class ProjectIntelligenceFoundationIntegrationTest {

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ScopeService scopeService;

    @Autowired
    private ProjectIntelligenceCoordinator coordinator;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void eventDrivenCollectorIsIdempotentAndProducesAppendOnlyFeatureSnapshot() {
        Fixture fixture = fixture();
        UUID revisionId = UUID.randomUUID();
        String triggerKey = "document-evidence:" + UUID.randomUUID();
        ProjectIntelligenceSignal signal = new ProjectIntelligenceSignal(
                fixture.projectId(),
                fixture.scopeId(),
                "DOCUMENT_EVIDENCE_RECORDED",
                "DOCUMENT_REVISION",
                revisionId,
                triggerKey,
                "{\"evidenceSnapshotId\":\"" + UUID.randomUUID() + "\",\"extractorCode\":\"DRAWING_V1\"}",
                Instant.now());

        coordinator.accept(signal);
        coordinator.accept(signal);

        assertEquals(1L, count("""
                SELECT COUNT(*) FROM project_intelligence_collector_jobs
                WHERE project_id = :projectId AND trigger_key = :triggerKey AND status = 'SUCCEEDED'
                """, fixture.projectId(), triggerKey));
        assertEquals(1L, count("""
                SELECT COUNT(*) FROM project_intelligence_feature_snapshots
                WHERE project_id = :projectId AND feature_code = 'DOCUMENT_EVIDENCE_AVAILABLE'
                """, fixture.projectId(), null));
    }

    @Test
    void recoveryOnlyRequeuesStaleCollectorJobsAndProcessesThem() {
        Fixture fixture = fixture();
        UUID jobId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        String triggerKey = "document-evidence:" + UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.sql("""
                        INSERT INTO project_intelligence_collector_jobs (
                            id, project_id, scope_id, collector_code, trigger_type, subject_type, subject_id,
                            trigger_key, payload_json, occurred_at, status, attempt_count, claimed_at,
                            created_at, updated_at)
                        VALUES (
                            :id, :projectId, :scopeId, 'DOCUMENT_EVIDENCE_COLLECTOR',
                            'DOCUMENT_EVIDENCE_RECORDED', 'DOCUMENT_REVISION', :subjectId,
                            :triggerKey, :payloadJson, :occurredAt, 'RUNNING', 1, :claimedAt,
                            :createdAt, :updatedAt)
                        """)
                .param("id", jobId)
                .param("projectId", fixture.projectId())
                .param("scopeId", fixture.scopeId())
                .param("subjectId", revisionId)
                .param("triggerKey", triggerKey)
                .param("payloadJson", "{\"evidenceSnapshotId\":\"" + UUID.randomUUID() + "\"}")
                .param("occurredAt", now.minus(10, ChronoUnit.MINUTES))
                .param("claimedAt", now.minus(10, ChronoUnit.MINUTES))
                .param("createdAt", now.minus(10, ChronoUnit.MINUTES))
                .param("updatedAt", now.minus(10, ChronoUnit.MINUTES))
                .update();

        coordinator.recoverIncompleteJobs();

        String status = jdbc.sql("SELECT status FROM project_intelligence_collector_jobs WHERE id = :id")
                .param("id", jobId)
                .query(String.class)
                .single();
        assertEquals("SUCCEEDED", status);
        assertEquals(1L, count("""
                SELECT COUNT(*) FROM project_intelligence_feature_snapshots
                WHERE project_id = :projectId AND feature_code = 'DOCUMENT_EVIDENCE_AVAILABLE'
                """, fixture.projectId(), null));
    }

    @Test
    void invalidSignalPayloadCannotBecomeProjectKnowledge() {
        Fixture fixture = fixture();
        ProjectIntelligenceSignal signal = new ProjectIntelligenceSignal(
                fixture.projectId(), fixture.scopeId(), "DOCUMENT_EVIDENCE_RECORDED",
                "DOCUMENT_REVISION", UUID.randomUUID(), "invalid-json:" + UUID.randomUUID(),
                "not-json", Instant.now());

        assertThrows(IllegalArgumentException.class, () -> coordinator.accept(signal));
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        var workspace = workspaceService.create("INT-" + suffix, "Intelligence " + suffix);
        var project = projectService.create(
                workspace.id(), "P-" + suffix, "Project " + suffix,
                null, null, null, "AED", "Asia/Dubai");
        var scope = scopeService.create(
                project.id(), null, "STAGE", "EXEC-" + suffix, "Execution " + suffix,
                null, null, null, "{}");
        return new Fixture(project.id(), scope.id());
    }

    private long count(String sql, UUID projectId, String triggerKey) {
        var query = jdbc.sql(sql).param("projectId", projectId);
        if (triggerKey != null) {
            query = query.param("triggerKey", triggerKey);
        }
        Long value = query.query(Long.class).single();
        return value == null ? 0L : value;
    }

    private record Fixture(UUID projectId, UUID scopeId) {
    }
}
