package com.yashu.projectcontrol;

import com.yashu.projectcontrol.commercial.CommercialService;
import com.yashu.projectcontrol.document.DocumentService;
import com.yashu.projectcontrol.access.IdentityService;
import com.yashu.projectcontrol.organization.OrganizationService;
import com.yashu.projectcontrol.participation.ParticipationService;
import com.yashu.projectcontrol.project.ProjectService;
import com.yashu.projectcontrol.scope.ScopeService;
import com.yashu.projectcontrol.verification.VerificationService;
import com.yashu.projectcontrol.workflow.AuthorizedWorkflowExecutionService;
import com.yashu.projectcontrol.workflow.WorkflowService;
import com.yashu.projectcontrol.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class VerificationMeasurementCommercialTraceIntegrationTest {

    @Autowired WorkspaceService workspaceService;
    @Autowired OrganizationService organizationService;
    @Autowired ProjectService projectService;
    @Autowired ParticipationService participationService;
    @Autowired ScopeService scopeService;
    @Autowired IdentityService identityService;
    @Autowired DocumentService documentService;
    @Autowired WorkflowService workflowService;
    @Autowired AuthorizedWorkflowExecutionService authorizedWorkflowExecutionService;
    @Autowired VerificationService verificationService;
    @Autowired CommercialService commercialService;

    @Test
    void partialVerificationReworkMeasurementValuationIpcAndPaymentRemainFullyTraceable() {
        var workspace = workspaceService.create("VERIFY-TRACE", "Verification Trace");
        var project = projectService.create(
                workspace.id(), "CHW-TRACE", "CHW Verification Trace", null,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), "AED", "Asia/Dubai");

        var client = organizationService.create("Trace Client PJSC", "Trace Client");
        var contractor = organizationService.create("Trace Contractor LLC", "Trace Contractor");
        var clientParticipant = participationService.create(
                project.id(), client.id(), "CLIENT", null, null, null);
        var contractorParticipant = participationService.create(
                project.id(), contractor.id(), "MAIN_CONTRACTOR", null, null, null);

        var construction = scopeService.create(
                project.id(), null, "STAGE", "CONSTRUCTION", "Construction", null,
                null, null, "{}");
        var zoneB = scopeService.create(
                project.id(), construction.id(), "ZONE", "ZONE_B", "Zone B", null,
                null, null, "{}");
        var chw = scopeService.create(
                project.id(), zoneB.id(), "ACTIVITY_GROUP", "CHW_WORK", "CHW Work", null,
                null, null, "{}");
        scopeService.setCapability(project.id(), chw.id(), "VERIFICATION", true, "{}");
        scopeService.setCapability(project.id(), chw.id(), "QUANTITY_MEASUREMENT", true, "{}");
        scopeService.setCapability(project.id(), chw.id(), "VALUATION", true, "{}");
        scopeService.setCapability(project.id(), chw.id(), "IPC", true, "{}");
        scopeService.setCapability(project.id(), chw.id(), "PAYMENT", true, "{}");
        scopeService.assignParticipant(
                project.id(), chw.id(), contractorParticipant.id(), "Executes CHW installation");
        scopeService.assignParticipant(
                project.id(), chw.id(), clientParticipant.id(), "Verifies and accepts CHW installation");

        var admin = identityService.createUser("trace-admin", "trace.admin@local.demo", "Trace Admin");
        identityService.addWorkspaceMembership(admin.id(), workspace.id(), "PROJECT_ADMIN", null, null);

        var contractorUser = identityService.createUser(
                "trace-contractor", "trace.contractor@local.demo", "Contractor QS");
        identityService.addOrganizationMembership(
                contractorUser.id(), contractor.id(), "SITE_QS", null, null);
        identityService.addScopeAssignment(
                contractorUser.id(), project.id(), chw.id(), contractorParticipant.id(),
                "SITE_QS", "MANAGE", null, null);

        var clientVerifier = identityService.createUser(
                "trace-client-verifier", "trace.verifier@local.demo", "Client Verifier");
        identityService.addOrganizationMembership(
                clientVerifier.id(), client.id(), "CLIENT_VERIFIER", null, null);
        identityService.addScopeAssignment(
                clientVerifier.id(), project.id(), chw.id(), clientParticipant.id(),
                "CLIENT_VERIFIER", "APPROVE", null, null);

        // Generic workflow remains the approval mechanism; verification does not create its own engine.
        var verificationWorkflow = workflowService.createDefinition(
                project.id(), "WORK_VERIFICATION", 1, "Work Verification",
                "WORK_ACCEPTANCE", "VERIFICATION");
        workflowService.addStep(
                verificationWorkflow.id(), 1, "CLIENT_VERIFICATION", "Client Verification",
                "ACCEPT", "{\"responsibility\":\"CLIENT_VERIFIER\",\"accessLevel\":\"APPROVE\"}", "{}");
        verificationWorkflow = workflowService.activateDefinition(verificationWorkflow.id());
        workflowService.setScopeBinding(project.id(), chw.id(), verificationWorkflow.id(), true, "{}");

        var evidenceDocument = documentService.create(
                project.id(), chw.id(), contractor.id(), "CHW-WVP-EVIDENCE", null,
                "WORK_VERIFICATION_EVIDENCE", "CHW installed quantity evidence", null,
                "CONTRACT_SHARED", "{}");
        var evidenceRevision = documentService.addRevision(
                evidenceDocument.id(), "A", "Installed CHW quantity evidence",
                "local://verification/chw-evidence-a.pdf",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "chw-evidence-a.pdf", "application/pdf", 2048L);

        // Attempt 1: contractor submits 320 m; client accepts 300 m and returns 20 m for rework.
        var first = verificationService.createPackage(
                contractorUser.id(), project.id(), chw.id(), "WVP-CHW-001",
                "INSTALLED_QUANTITY", contractor.id(), null);
        var firstItem = verificationService.addItem(
                contractorUser.id(), project.id(), first.id(), first.version(),
                "scope://" + project.id() + "/" + chw.id() + "/chw-installed",
                null, new BigDecimal("320"), "m", "320 m CHW installed and offered for verification");
        first = verificationService.getPackage(contractorUser.id(), project.id(), first.id()).verificationPackage();
        verificationService.addEvidence(
                contractorUser.id(), project.id(), first.id(), first.version(), evidenceRevision.id(),
                "INSTALLATION_RECORD", "CONTRACT_SHARED", true);
        first = verificationService.getPackage(contractorUser.id(), project.id(), first.id()).verificationPackage();
        first = verificationService.submit(
                contractorUser.id(), project.id(), first.id(), first.version(), verificationWorkflow.id());

        var firstBundle = verificationService.getPackage(clientVerifier.id(), project.id(), first.id());
        assertNotNull(firstBundle.workflowInstanceId());
        authorizedWorkflowExecutionService.act(
                clientVerifier.id(), firstBundle.workflowInstanceId(), "COMPLETE_STEP", "ACCEPT",
                null, "Verified on site; 20 m requires rework", "{}");

        var firstDecision = verificationService.decide(
                clientVerifier.id(), project.id(), first.id(), firstItem.id(), first.version(), client.id(),
                "PARTIALLY_ACCEPTED", new BigDecimal("300"), new BigDecimal("20"), "m",
                "300 m accepted; 20 m returned for rework");
        first = verificationService.getPackage(clientVerifier.id(), project.id(), first.id()).verificationPackage();
        verificationService.decide(
                clientVerifier.id(), project.id(), first.id(), null, first.version(), client.id(),
                "PARTIALLY_ACCEPTED", null, null, null, "Partial acceptance recorded");
        first = verificationService.getPackage(clientVerifier.id(), project.id(), first.id()).verificationPackage();
        assertEquals("PARTIALLY_ACCEPTED", first.status());

        var firstMeasurement = verificationService.createMeasurement(
                clientVerifier.id(), project.id(), first.id(), firstDecision.id(),
                new BigDecimal("320"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15));
        money("320", firstMeasurement.submittedQuantity());
        money("320", firstMeasurement.measuredQuantity());
        money("300", firstMeasurement.acceptedQuantity());
        money("20", firstMeasurement.rejectedQuantity());

        // Attempt 2 explicitly references attempt 1 instead of overwriting its history.
        var followUp = verificationService.createPackage(
                contractorUser.id(), project.id(), chw.id(), "WVP-CHW-002",
                "REWORK_QUANTITY", contractor.id(), first.id());
        assertEquals(first.id(), followUp.parentPackageId());
        var followUpItem = verificationService.addItem(
                contractorUser.id(), project.id(), followUp.id(), followUp.version(),
                firstItem.subjectResourceReference(), null, new BigDecimal("20"), "m",
                "20 m CHW reworked and resubmitted");
        followUp = verificationService.getPackage(contractorUser.id(), project.id(), followUp.id()).verificationPackage();
        verificationService.addEvidence(
                contractorUser.id(), project.id(), followUp.id(), followUp.version(), evidenceRevision.id(),
                "REWORK_COMPLETION_RECORD", "CONTRACT_SHARED", true);
        followUp = verificationService.getPackage(contractorUser.id(), project.id(), followUp.id()).verificationPackage();
        followUp = verificationService.submit(
                contractorUser.id(), project.id(), followUp.id(), followUp.version(), verificationWorkflow.id());

        var followUpBundle = verificationService.getPackage(clientVerifier.id(), project.id(), followUp.id());
        authorizedWorkflowExecutionService.act(
                clientVerifier.id(), followUpBundle.workflowInstanceId(), "COMPLETE_STEP", "ACCEPT",
                null, "Rework verified and accepted", "{}");
        var followUpDecision = verificationService.decide(
                clientVerifier.id(), project.id(), followUp.id(), followUpItem.id(), followUp.version(), client.id(),
                "ACCEPTED", new BigDecimal("20"), BigDecimal.ZERO, "m", "Remaining 20 m accepted");
        followUp = verificationService.getPackage(clientVerifier.id(), project.id(), followUp.id()).verificationPackage();
        verificationService.decide(
                clientVerifier.id(), project.id(), followUp.id(), null, followUp.version(), client.id(),
                "ACCEPTED", null, null, null, "Follow-up verification accepted");
        followUp = verificationService.getPackage(clientVerifier.id(), project.id(), followUp.id()).verificationPackage();
        assertEquals("ACCEPTED", followUp.status());

        var followUpMeasurement = verificationService.createMeasurement(
                clientVerifier.id(), project.id(), followUp.id(), followUpDecision.id(),
                new BigDecimal("20"), LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 20));
        money("20", followUpMeasurement.acceptedQuantity());
        money("0", followUpMeasurement.rejectedQuantity());

        var scopeTruth = verificationService.scopeSummary(clientVerifier.id(), project.id(), chw.id());
        money("320", scopeTruth.acceptedQuantity());
        money("20", scopeTruth.rejectedQuantity());
        assertEquals(2, scopeTruth.measurementCount());

        // Contractual valuation is derived only from accepted measurement truth.
        var contract = commercialService.createContract(
                admin.id(), project.id(), clientParticipant.id(), contractorParticipant.id(),
                "TRACE-CHW-001", "MAIN_CONTRACT", "AED", new BigDecimal("128000"), "CONTRACT_SHARED");
        var quantityItem = commercialService.createContractItem(
                admin.id(), project.id(), contract.id(), chw.id(), "CHW-QTY",
                "CHW measured and accepted quantity", "QUANTITY_RATE", "m",
                new BigDecimal("320"), new BigDecimal("400"), new BigDecimal("128000"), null);

        assertThrows(ResponseStatusException.class, () -> commercialService.createValuation(
                contractorUser.id(), project.id(), contract.id(), quantityItem.id(), "VAL-INVALID",
                null, null, evidenceRevision.id(), null, new BigDecimal("120000"), BigDecimal.ZERO, BigDecimal.ZERO));

        var valuation300 = commercialService.createValuation(
                contractorUser.id(), project.id(), contract.id(), quantityItem.id(), "VAL-CHW-300",
                null, null, null, firstMeasurement.id(), null, BigDecimal.ZERO, BigDecimal.ZERO);
        money("300", valuation300.acceptedQuantity());
        money("120000", valuation300.currentValue());
        assertEquals(firstMeasurement.id(), valuation300.measurementId());

        var valuation20 = commercialService.createValuation(
                contractorUser.id(), project.id(), contract.id(), quantityItem.id(), "VAL-CHW-20",
                null, null, null, followUpMeasurement.id(), null, BigDecimal.ZERO, BigDecimal.ZERO);
        money("20", valuation20.acceptedQuantity());
        money("8000", valuation20.currentValue());
        money("128000", valuation20.cumulativeValue());
        assertEquals(followUpMeasurement.id(), valuation20.measurementId());

        // One accepted measurement cannot be valued twice for the same contract item.
        assertThrows(Exception.class, () -> commercialService.createValuation(
                contractorUser.id(), project.id(), contract.id(), quantityItem.id(), "VAL-DUPLICATE",
                null, null, null, firstMeasurement.id(), null, BigDecimal.ZERO, BigDecimal.ZERO));

        var ipc = commercialService.createPaymentApplication(
                contractorUser.id(), project.id(), contract.id(), "IPC-CHW-001",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 10, 15), null);
        commercialService.addPaymentApplicationLine(
                contractorUser.id(), project.id(), ipc.id(), valuation300.id(), new BigDecimal("120000"));
        commercialService.addPaymentApplicationLine(
                contractorUser.id(), project.id(), ipc.id(), valuation20.id(), new BigDecimal("8000"));
        ipc = commercialService.submitPaymentApplication(
                contractorUser.id(), project.id(), ipc.id(), ipc.version());
        ipc = commercialService.certifyPaymentApplication(
                clientVerifier.id(), project.id(), ipc.id(), ipc.version(),
                List.of(
                        new CommercialService.CertificationLine(
                                valuation300.id(), new BigDecimal("116000"), "AED 4,000 withheld"),
                        new CommercialService.CertificationLine(
                                valuation20.id(), new BigDecimal("8000"), "Fully certified")));
        money("124000", ipc.certifiedAmount());

        var payment = commercialService.recordPayment(
                clientVerifier.id(), project.id(), ipc.id(), "PAY-CHW-001", new BigDecimal("100000"),
                Instant.parse("2026-10-20T10:00:00Z"), null);

        var firstPackageId = first.id();
        var followUpPackageId = followUp.id();
        var trace = commercialService.paymentTrace(clientVerifier.id(), project.id(), payment.id());
        assertEquals("IPC-CHW-001", trace.paymentApplication().applicationNumber());
        assertEquals(2, trace.lines().size());
        assertTrue(trace.lines().stream().allMatch(line ->
                line.measurementId() != null
                        && line.verificationPackageId() != null
                        && line.verificationTrace() != null
                        && "ACCEPTED_MEASUREMENT_TYPED_TRACE_COMPLETE".equals(line.verificationMappingStatus())));
        assertTrue(trace.lines().stream().allMatch(line ->
                !line.verificationTrace().evidence().isEmpty()
                        && !line.verificationTrace().decisions().isEmpty()));
        assertTrue(trace.lines().stream().anyMatch(line ->
                line.verificationPackageId().equals(firstPackageId)
                        && line.verificationTrace().decisions().stream().anyMatch(decision ->
                        decision.actorUserId().equals(clientVerifier.id())
                                && decision.actorOrganizationId().equals(client.id())
                                && "PARTIALLY_ACCEPTED".equals(decision.decision()))));
        assertTrue(trace.lines().stream().anyMatch(line ->
                line.verificationPackageId().equals(followUpPackageId)
                        && line.verificationTrace().verificationPackage().parentPackageId().equals(firstPackageId)));
    }

    private static void money(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "Expected " + expected + " but was " + actual);
    }
}
