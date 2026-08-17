package com.whatsappbot.document;

import com.whatsappbot.infrastructure.whatsapp.WhatsAppGraphClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Exercises the real application path against the Postgres used by CI:
 *
 * contractor creates shop drawing -> workflow is attached -> submission seeds approval steps ->
 * assignment trigger writes durable outbox -> audience dispatch creates in-app + WhatsApp delivery ->
 * mocked Meta transport succeeds -> contractor / consultant / client decisions advance the same
 * approval to APPROVED.
 *
 * The only mocked boundary is the external Meta Graph transport. Everything else in this test is
 * the production Spring/JPA/JDBC/Flyway implementation.
 */
@SpringBootTest(properties = {
        "app.document-notifications.whatsapp-enabled=true",
        "app.document-notifications.email-enabled=false",
        "app.document-notifications.dispatch-ms=3600000",
        "app.document-notifications.delivery-ms=3600000",
        "app.document-notifications.sla-scan-ms=3600000",
        "app.document-notifications.purge-ms=3600000"
})
@DirtiesContext
class EnterpriseProjectControlE2ETest {

    private static final UUID PROJECT = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID CONTRACTOR_DC = UUID.fromString("40000000-0000-0000-0000-000000000016");
    private static final UUID CONSULTANT_MANAGER = UUID.fromString("40000000-0000-0000-0000-000000000006");
    private static final UUID CLIENT_DIRECTOR = UUID.fromString("40000000-0000-0000-0000-000000000002");

    @Autowired DocumentService documentService;
    @Autowired WorkflowNotificationDispatcher notificationDispatcher;
    @Autowired WorkflowNotificationService notificationService;
    @Autowired JdbcTemplate jdbc;

    @MockBean WhatsAppGraphClient whatsApp;

    @Test
    @Transactional
    @DisplayName("shop drawing travels contractor -> consultant -> client and produces in-app + WhatsApp notification")
    void shopDrawingApprovalAndNotificationJourney() throws Exception {
        UUID tenant = jdbc.queryForObject(
                "select id from tenants where tenant_code='DEMO'", UUID.class);
        assertThat(tenant).isNotNull();

        // Persisted demo data leaves WhatsApp disabled. Enable a synthetic destination only for
        // this rolled-back test; the Graph client itself is mocked, so no external message exists.
        notificationService.preferences(tenant, CONTRACTOR_DC, false, true, "+971500000016");

        DocumentEntity document = documentService.createDocument(
                tenant,
                CONTRACTOR_DC,
                new DocumentService.CreateDocumentRequest(
                        "Kitchen Cabinet Shop Drawing - Milking Parlor",
                        "SHOP_DRAWING",
                        "E2E fixture: subcontractor/contractor submission requiring consultant and client review.",
                        new String[]{"E2E", "ARCHITECTURE", "MILKING-PARLOR"},
                        PROJECT),
                null);

        assertThat(document.getProjectId()).isEqualTo(PROJECT);
        assertThat(document.getWorkflowId()).isEqualTo(
                UUID.fromString("b0000000-0000-0000-0000-000000000001"));
        assertThat(document.getDocumentCode()).isNotBlank();

        DocumentApprovalEntity approval = documentService.submitForApproval(tenant, CONTRACTOR_DC, document.getId());
        assertThat(approval.getStatus()).isEqualTo("PENDING");

        Integer stepCount = jdbc.queryForObject(
                "select count(*) from document_approval_steps where approval_id=?",
                Integer.class, approval.getId());
        assertThat(stepCount).isEqualTo(3);

        String firstReviewer = jdbc.queryForObject(
                "select reviewer_email from document_approval_steps where approval_id=? and step_index=0",
                String.class, approval.getId());
        assertThat(firstReviewer).isEqualTo("document.controller@gulfbuild.demo");

        int recipients = notificationDispatcher.dispatchBatch();
        assertThat(recipients).isGreaterThanOrEqualTo(1);
        assertThat(notificationService.unread(tenant, CONTRACTOR_DC)).isGreaterThanOrEqualTo(1);

        int delivered = notificationService.deliverBatch();
        assertThat(delivered).isGreaterThanOrEqualTo(1);
        verify(whatsApp, atLeastOnce()).sendTextMessageChecked(
                any(), eq("+971500000016"), contains("Kitchen Cabinet Shop Drawing"));

        // Contractor document-control gate.
        documentService.decideStep(tenant, CONTRACTOR_DC, approval.getId(),
                "APPROVED", "Register, numbering and submission package checked.");
        assertCurrentStep(approval.getId(), 1);

        // Party-role stages are intentionally decided by MANAGER/ADMIN users. A project-level
        // REVIEWER can act when a workflow explicitly assigns that reviewer by email.
        documentService.decideStep(tenant, CONSULTANT_MANAGER, approval.getId(),
                "APPROVED", "Technical review complete; proceed for client decision.");
        assertCurrentStep(approval.getId(), 2);

        // Client decision closes the approval and the document.
        documentService.decideStep(tenant, CLIENT_DIRECTOR, approval.getId(),
                "APPROVED", "Client approval granted.");

        String approvalStatus = jdbc.queryForObject(
                "select status from document_approvals where id=?", String.class, approval.getId());
        String documentStatus = jdbc.queryForObject(
                "select status from documents where id=?", String.class, document.getId());
        assertThat(approvalStatus).isEqualTo("APPROVED");
        assertThat(documentStatus).isEqualTo("APPROVED");

        Integer auditCount = jdbc.queryForObject(
                "select count(*) from document_audit_events where document_id=?",
                Integer.class, document.getId());
        assertThat(auditCount).isGreaterThanOrEqualTo(5);
    }

    private void assertCurrentStep(UUID approvalId, int expected) {
        Integer step = jdbc.queryForObject(
                "select current_step from document_approvals where id=?", Integer.class, approvalId);
        assertThat(step).isEqualTo(expected);
    }
}
