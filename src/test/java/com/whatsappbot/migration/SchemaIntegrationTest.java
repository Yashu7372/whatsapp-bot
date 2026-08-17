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

/** Boots the whole application against a throwaway Postgres and verifies migration/runtime assumptions. */
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
        assertThat(constraintAllows("workflow_notification_outbox", "PROCESSING")).isTrue();
        assertThat(constraintAllows("workflow_notification_deliveries", "SKIPPED")).isTrue();
    }

    @Test
    @DisplayName("obsolete V31 workflow enrichment trigger is removed")
    void obsoleteWorkflowEnrichmentTriggerIsGone() {
        Integer oldTrigger = jdbc.queryForObject("""
                select count(*) from pg_trigger t
                  join pg_class c on c.oid=t.tgrelid
                 where c.relname='document_approval_steps'
                   and t.tgname='trg_enrich_document_approval_step'
                   and not t.tgisinternal
                """, Integer.class);
        Integer initialParallelTrigger = jdbc.queryForObject("""
                select count(*) from pg_trigger t
                  join pg_class c on c.oid=t.tgrelid
                 where c.relname='document_approval_steps'
                   and t.tgname='trg_start_initial_parallel_step_sla'
                   and not t.tgisinternal
                """, Integer.class);

        assertThat(oldTrigger).isZero();
        assertThat(initialParallelTrigger).isEqualTo(1);
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
