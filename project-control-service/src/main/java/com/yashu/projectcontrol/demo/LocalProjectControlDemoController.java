package com.yashu.projectcontrol.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * Local-only manifest used by the Postman demo collection.
 *
 * <p>Business resources still use their generated database identifiers. This endpoint
 * resolves the canonical demo codes/numbers to those identifiers so demo clients do
 * not hardcode random UUIDs or bypass normal Project Control APIs.</p>
 */
@RestController
@Profile("local")
@RequestMapping("/api/local/demo-scenario")
@ConditionalOnProperty(name = "project-control.demo.enabled", havingValue = "true", matchIfMissing = true)
class LocalProjectControlDemoController {

    private final JdbcClient jdbc;

    LocalProjectControlDemoController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    ScenarioView scenario() {
        UUID workspaceId = requiredUuid("""
                SELECT id FROM workspaces WHERE code = :code
                """, "code", LocalProjectControlDemoBootstrap.WORKSPACE_CODE);
        UUID projectId = requiredUuid("""
                SELECT id FROM projects WHERE workspace_id = :id AND code = :code
                """, "id", workspaceId, "code", LocalProjectControlDemoBootstrap.PROJECT_CODE);
        UUID scopeId = requiredUuid("""
                SELECT id FROM project_scopes WHERE project_id = :id AND code = :code
                """, "id", projectId, "code", LocalProjectControlDemoBootstrap.CHW_SCOPE_CODE);

        UUID clientOrganizationId = requiredUuid("""
                SELECT id FROM organizations WHERE legal_name = :name
                """, "name", LocalProjectControlDemoBootstrap.CLIENT_LEGAL_NAME);
        UUID contractorOrganizationId = requiredUuid("""
                SELECT id FROM organizations WHERE legal_name = :name
                """, "name", LocalProjectControlDemoBootstrap.CONTRACTOR_LEGAL_NAME);
        UUID consultantOrganizationId = requiredUuid("""
                SELECT id FROM organizations WHERE legal_name = :name
                """, "name", LocalProjectControlDemoBootstrap.CONSULTANT_LEGAL_NAME);

        UUID documentId = requiredUuid("""
                SELECT id FROM documents WHERE project_id = :id AND document_number = :code
                """, "id", projectId, "code", LocalProjectControlDemoBootstrap.DOCUMENT_NUMBER);
        UUID revisionId = requiredUuid("""
                SELECT id FROM document_revisions WHERE document_id = :id AND revision_code = :code
                """, "id", documentId, "code", LocalProjectControlDemoBootstrap.DOCUMENT_REVISION);

        UUID documentWorkflowDefinitionId = requiredUuid("""
                SELECT id FROM workflow_definitions
                WHERE project_id = :id AND code = :code AND version = 1
                """, "id", projectId, "code", LocalProjectControlDemoBootstrap.DOCUMENT_WORKFLOW_CODE);
        UUID documentWorkflowInstanceId = requiredUuid("""
                SELECT workflow_instance_id
                FROM document_workflow_instances
                WHERE document_id = :id
                ORDER BY created_at ASC
                LIMIT 1
                """, "id", documentId);
        UUID verificationWorkflowDefinitionId = requiredUuid("""
                SELECT id FROM workflow_definitions
                WHERE project_id = :id AND code = :code AND version = 1
                """, "id", projectId, "code", LocalProjectControlDemoBootstrap.VERIFICATION_WORKFLOW_CODE);

        UUID firstVerificationPackageId = requiredUuid("""
                SELECT id FROM verification_packages
                WHERE project_id = :id AND package_number = :code
                """, "id", projectId, "code", LocalProjectControlDemoBootstrap.FIRST_VERIFICATION_PACKAGE);
        UUID firstVerificationItemId = requiredUuid("""
                SELECT id FROM verification_items
                WHERE verification_package_id = :id
                ORDER BY created_at ASC
                LIMIT 1
                """, "id", firstVerificationPackageId);

        UUID contractId = requiredUuid("""
                SELECT id FROM contracts WHERE project_id = :id AND contract_number = :code
                """, "id", projectId, "code", LocalProjectControlDemoBootstrap.CONTRACT_NUMBER);
        UUID contractItemId = requiredUuid("""
                SELECT id FROM contract_items WHERE contract_id = :id AND item_code = :code
                """, "id", contractId, "code", LocalProjectControlDemoBootstrap.CONTRACT_ITEM_CODE);

        long firstPackageVersion = requiredLong("""
                SELECT version FROM verification_packages WHERE id = :id
                """, "id", firstVerificationPackageId);
        String firstPackageStatus = requiredString("""
                SELECT status FROM verification_packages WHERE id = :id
                """, "id", firstVerificationPackageId);
        String documentWorkflowStatus = requiredString("""
                SELECT status FROM workflow_instances WHERE id = :id
                """, "id", documentWorkflowInstanceId);

        return new ScenarioView(
                "AURELIA_CHW_TRACE",
                workspaceId,
                projectId,
                scopeId,
                clientOrganizationId,
                contractorOrganizationId,
                consultantOrganizationId,
                documentId,
                revisionId,
                documentWorkflowDefinitionId,
                documentWorkflowInstanceId,
                verificationWorkflowDefinitionId,
                firstVerificationPackageId,
                firstVerificationItemId,
                firstPackageVersion,
                contractId,
                contractItemId,
                documentWorkflowStatus,
                firstPackageStatus,
                Map.of(
                        "admin", "admin@local.demo",
                        "site", "site@local.demo",
                        "qce", "qce@local.demo",
                        "qcdc", "qcdc@local.demo",
                        "inspector", "inspector@local.demo",
                        "re", "re@local.demo",
                        "viewer", "viewer@local.demo",
                        "contractorQs", "contractor.qs@local.demo",
                        "clientQs", "client.qs@local.demo"),
                "Delete project-control-local.mv.db and ./project-control-files, then restart with the local profile to replay from a clean state.");
    }

    private UUID requiredUuid(String sql, String name1, Object value1) {
        return jdbc.sql(sql)
                .param(name1, value1)
                .query(UUID.class)
                .optional()
                .orElseThrow(LocalProjectControlDemoController::notSeeded);
    }

    private UUID requiredUuid(
            String sql,
            String name1,
            Object value1,
            String name2,
            Object value2) {
        return jdbc.sql(sql)
                .param(name1, value1)
                .param(name2, value2)
                .query(UUID.class)
                .optional()
                .orElseThrow(LocalProjectControlDemoController::notSeeded);
    }

    private long requiredLong(String sql, String name, Object value) {
        Long result = jdbc.sql(sql)
                .param(name, value)
                .query(Long.class)
                .optional()
                .orElseThrow(LocalProjectControlDemoController::notSeeded);
        return result;
    }

    private String requiredString(String sql, String name, Object value) {
        return jdbc.sql(sql)
                .param(name, value)
                .query(String.class)
                .optional()
                .orElseThrow(LocalProjectControlDemoController::notSeeded);
    }

    private static ResponseStatusException notSeeded() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Local Project Control demo scenario is not seeded. Start with the local profile and project-control.demo.enabled=true.");
    }

    record ScenarioView(
            String scenarioCode,
            UUID workspaceId,
            UUID projectId,
            UUID chwScopeId,
            UUID clientOrganizationId,
            UUID contractorOrganizationId,
            UUID consultantOrganizationId,
            UUID documentId,
            UUID revisionId,
            UUID documentWorkflowDefinitionId,
            UUID documentWorkflowInstanceId,
            UUID verificationWorkflowDefinitionId,
            UUID firstVerificationPackageId,
            UUID firstVerificationItemId,
            long firstVerificationPackageVersion,
            UUID contractId,
            UUID contractItemId,
            String documentWorkflowStatus,
            String firstVerificationPackageStatus,
            Map<String, String> accounts,
            String resetHint) {
    }
}
