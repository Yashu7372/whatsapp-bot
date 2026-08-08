package com.whatsappbot.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole application against a throwaway Postgres.
 *
 * <p>This is the test that was missing. Every other test in this repository is a Mockito unit test,
 * so the suite could pass while the application was unable to start at all — which is exactly what
 * happened when two migrations both claimed version 32. Running Flyway end to end and letting
 * Hibernate validate its mappings against the resulting schema turns two entire classes of
 * production-only failure into build failures.
 *
 * <p>The image is pgvector's Postgres build because the knowledge-base migration installs the
 * {@code vector} extension; stock {@code postgres:16} cannot run this migration set.
 *
 * <p>{@code disabledWithoutDocker} keeps the suite runnable on a machine with no Docker daemon:
 * the class is skipped rather than failing. CI has Docker, so there it genuinely runs.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "whatsapp.verify-token=test-verify-token",
        "whatsapp.fallback-access-token=test-access-token",
        "whatsapp.mock-send-enabled=true",
        "ai.provider=OLLAMA"
})
class SchemaIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("every migration applies and Hibernate validates against the result")
    void schemaMatchesEntities() {
        // Reaching this point already proves it: the context only starts if Flyway resolved and
        // applied every script and ddl-auto=validate found no mapping drift.
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = true", Integer.class);
        assertThat(applied).isNotNull().isPositive();

        List<String> failed = jdbc.queryForList(
                "select script from flyway_schema_history where success = false", String.class);
        assertThat(failed).isEmpty();
    }

    @Test
    @DisplayName("notification dispatch tables carry the states the workers rely on")
    void notificationStatesArePersistable() {
        // The dispatcher parks a claim in PROCESSING and the worker parks a disabled channel in
        // SKIPPED; if either check constraint rejected the value, the pipeline would fail only
        // under load, in production.
        assertThat(constraintAllows("workflow_notification_outbox", "PROCESSING")).isTrue();
        assertThat(constraintAllows("workflow_notification_deliveries", "SKIPPED")).isTrue();
    }

    private boolean constraintAllows(String table, String status) {
        List<String> definitions = jdbc.queryForList("""
                select pg_get_constraintdef(c.oid)
                  from pg_constraint c join pg_class t on t.oid = c.conrelid
                 where t.relname = ? and c.contype = 'c'
                """, String.class, table);
        return definitions.stream().anyMatch(d -> d.contains(status));
    }
}
