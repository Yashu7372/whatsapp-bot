package com.yashu.projectcontrol;

import com.yashu.projectcontrol.commercial.CommercialService;
import com.yashu.projectcontrol.cost.CostService;
import com.yashu.projectcontrol.document.DocumentService;
import com.yashu.projectcontrol.financialreporting.FinancialReadService;
import com.yashu.projectcontrol.access.IdentityService;
import com.yashu.projectcontrol.organization.OrganizationService;
import com.yashu.projectcontrol.participation.ParticipationService;
import com.yashu.projectcontrol.project.ProjectService;
import com.yashu.projectcontrol.scope.ScopeService;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class FinancialControlAndBillingIntegrationTest {

    @Autowired WorkspaceService workspaceService;
    @Autowired OrganizationService organizationService;
    @Autowired ProjectService projectService;
    @Autowired ParticipationService participationService;
    @Autowired ScopeService scopeService;
    @Autowired IdentityService identityService;
    @Autowired DocumentService documentService;
    @Autowired CostService costService;
    @Autowired CommercialService commercialService;
    @Autowired FinancialReadService financialReadService;

    @Test
    void costControlBillingCashFlowAndEvidenceRemainSeparateButTraceable() {
        var workspace = workspaceService.create("FIN-FOUNDATION", "Financial Foundation");
        var project = projectService.create(
                workspace.id(), "AURELIA-CREEK", "Aurelia Creek Residences", null,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), "AED", "Asia/Dubai");

        var client = organizationService.create("Aurelia Developments PJSC", "Aurelia");
        var contractor = organizationService.create("GulfBuild Contracting LLC", "GulfBuild");
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
                project.id(), zoneB.id(), "ACTIVITY_GROUP", "CHW_INSTALL", "CHW Installation", null,
                null, null, "{}");
        scopeService.setCapability(project.id(), chw.id(), "BUDGET_CONTROL", true, "{}");
        scopeService.setCapability(project.id(), chw.id(), "VALUATION", true, "{}");
        scopeService.setCapability(project.id(), chw.id(), "IPC", true, "{}");
        scopeService.setCapability(project.id(), chw.id(), "PAYMENT", true, "{}");
        scopeService.assignParticipant(
                project.id(), chw.id(), contractorParticipant.id(), "Execution and cost responsibility");

        var admin = identityService.createUser(
                "fin-admin", "fin.admin@local.demo", "Financial Admin");
        identityService.addWorkspaceMembership(admin.id(), workspace.id(), "PROJECT_ADMIN", null, null);

        var contractorUser = identityService.createUser(
                "gulfbuild-commercial", "commercial@gulfbuild.demo", "GulfBuild Commercial");
        identityService.addOrganizationMembership(
                contractorUser.id(), contractor.id(), "COMMERCIAL_MANAGER", null, null);
        identityService.addScopeAssignment(
                contractorUser.id(), project.id(), chw.id(), contractorParticipant.id(),
                "COMMERCIAL_MANAGER", "MANAGE", null, null);

        var clientUser = identityService.createUser(
                "aurelia-commercial", "commercial@aurelia.demo", "Aurelia Commercial");
        identityService.addOrganizationMembership(
                clientUser.id(), client.id(), "COMMERCIAL_MANAGER", null, null);

        // ---- Separate Project Scope and Cost Breakdown Structure ----
        var internalCbs = costService.createStructure(
                contractorUser.id(), project.id(), contractor.id(), null,
                "GB-COST", "GulfBuild Internal Cost", "INTERNAL_COST");
        var mepCost = costService.createNode(
                contractorUser.id(), project.id(), internalCbs.id(), null,
                "MEP", "MEP", "DISCIPLINE", 10);
        var chwCost = costService.createNode(
                contractorUser.id(), project.id(), internalCbs.id(), mepCost.id(),
                "CHW", "Chilled Water", "SYSTEM", 20);
        var scopeLink = costService.linkScope(
                contractorUser.id(), project.id(), chwCost.id(), chw.id(),
                new BigDecimal("100"), "ALLOCATION");
        assertNotEquals(chw.id(), chwCost.id());
        assertEquals(chw.id(), scopeLink.scopeId());
        assertEquals(chwCost.id(), scopeLink.costNodeId());

        // ---- Versioned budget + deterministic exposure ----
        var budget = costService.createBudgetVersion(
                contractorUser.id(), project.id(), internalCbs.id(), "ORIGINAL", "AED");
        costService.addBudgetLine(
                contractorUser.id(), project.id(), budget.id(), chwCost.id(), chw.id(),
                new BigDecimal("100000"), "Original CHW control budget");
        budget = costService.submitBudget(contractorUser.id(), project.id(), budget.id(), budget.version());
        budget = costService.approveBudget(contractorUser.id(), project.id(), budget.id(), budget.version());
        assertEquals("APPROVED", budget.status());

        var commitment = costService.createCommitment(
                contractorUser.id(), project.id(), contractor.id(), null, null, chw.id(), chwCost.id(),
                "PO-CHW-001", new BigDecimal("60000"), "AED",
                Instant.parse("2026-09-01T08:00:00Z"), null);
        costService.postActualCost(
                contractorUser.id(), project.id(), contractor.id(), chw.id(), chwCost.id(), commitment.id(),
                "SUPPLIER_INVOICE", "INV-CHW-001", null, new BigDecimal("20000"), "AED",
                LocalDate.of(2026, 9, 10), null);
        costService.setForecast(
                contractorUser.id(), project.id(), contractor.id(), chw.id(), chwCost.id(),
                LocalDate.of(2026, 10, 1), new BigDecimal("30000"), "AED",
                "Remaining installation forecast", null);

        var cost = costService.summary(contractorUser.id(), project.id(), chwCost.id());
        money("100000", cost.originalBudget());
        money("100000", cost.currentBudget());
        money("60000", cost.committed());
        money("20000", cost.actual());
        money("40000", cost.openCommitment());
        money("30000", cost.remainingForecast());
        money("50000", cost.eac());
        money("50000", cost.vac());
        money("60000", cost.budgetExposure());
        money("40000", cost.availableBudget());

        var allow = costService.budgetCheck(
                contractorUser.id(), project.id(), contractor.id(), chw.id(), chwCost.id(),
                new BigDecimal("30000"), "PROCUREMENT:CHW-VALVES");
        assertEquals("ALLOW", allow.decision());
        var block = costService.budgetCheck(
                contractorUser.id(), project.id(), contractor.id(), chw.id(), chwCost.id(),
                new BigDecimal("50000"), "PROCUREMENT:CHW-PUMPS");
        assertEquals("BLOCK", block.decision());

        // Client project participation does not disclose contractor-private cost/margin.
        assertThrows(ResponseStatusException.class,
                () -> costService.summary(clientUser.id(), project.id(), chwCost.id()));

        // ---- Controlled evidence + external commercial chain ----
        var evidenceDocument = documentService.create(
                project.id(), chw.id(), contractor.id(), "GB-CHW-MILESTONE-001", null,
                "MILESTONE_EVIDENCE", "CHW milestone completion evidence", null,
                "CONTRACT_SHARED", "{}");
        var evidenceRevision = documentService.addRevision(
                evidenceDocument.id(), "A", "Submitted commercial evidence",
                "local://evidence/chw-milestone-a.pdf",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "chw-milestone-a.pdf", "application/pdf", 1024L);

        var contract = commercialService.createContract(
                admin.id(), project.id(), clientParticipant.id(), contractorParticipant.id(),
                "AUR-GB-001", "MAIN_CONTRACT", "AED", new BigDecimal("120000"), "CONTRACT_SHARED");
        var milestone = commercialService.createContractItem(
                admin.id(), project.id(), contract.id(), chw.id(), "CHW-MS-01",
                "Complete CHW milestone", "MILESTONE", null, null, null,
                new BigDecimal("120000"), LocalDate.of(2026, 10, 15));
        var valuation = commercialService.createValuation(
                contractorUser.id(), project.id(), contract.id(), milestone.id(), "VAL-001",
                "DOCUMENT_REVISION", "Milestone evidence revision A", evidenceRevision.id(),
                new BigDecimal("120000"), BigDecimal.ZERO, BigDecimal.ZERO);
        money("120000", valuation.eligibleValue());
        assertEquals(evidenceRevision.id(), valuation.sourceDocumentRevisionId());

        var application = commercialService.createPaymentApplication(
                contractorUser.id(), project.id(), contract.id(), "IPC-001",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 10, 15), evidenceRevision.id());
        commercialService.addPaymentApplicationLine(
                contractorUser.id(), project.id(), application.id(), valuation.id(), new BigDecimal("120000"));
        application = commercialService.submitPaymentApplication(
                contractorUser.id(), project.id(), application.id(), application.version());
        application = commercialService.certifyPaymentApplication(
                clientUser.id(), project.id(), application.id(), application.version(),
                List.of(new CommercialService.CertificationLine(
                        valuation.id(), new BigDecimal("118000"), "AED 2,000 withheld pending closeout")));
        money("118000", application.certifiedAmount());

        var payment = commercialService.recordPayment(
                clientUser.id(), project.id(), application.id(), "PAY-001", new BigDecimal("100000"),
                Instant.parse("2026-10-20T10:00:00Z"), evidenceRevision.id());
        var commercialSummary = commercialService.contractSummary(
                clientUser.id(), project.id(), contract.id());
        money("120000", commercialSummary.originalValue());
        money("118000", commercialSummary.certifiedToDate());
        money("100000", commercialSummary.paidToDate());
        money("18000", commercialSummary.outstandingCertified());

        // Non-quantity milestone valuation remains directly traceable to its controlled document revision.
        // Verification/measurement IDs are intentionally null because those dimensions do not apply to this valuation method.
        var trace = commercialService.paymentTrace(clientUser.id(), project.id(), payment.id());
        assertEquals("IPC-001", trace.paymentApplication().applicationNumber());
        assertEquals(1, trace.lines().size());
        assertEquals(evidenceRevision.id(), trace.lines().getFirst().controlledEvidence().revisionId());
        assertNull(trace.lines().getFirst().verificationPackageId());
        assertNull(trace.lines().getFirst().measurementId());
        assertEquals("DIRECT_CONTROLLED_DOCUMENT_REVISION", trace.lines().getFirst().verificationMappingStatus());

        // Quantity-rate billing still fails closed when no accepted Measurement is supplied.
        var quantityItem = commercialService.createContractItem(
                admin.id(), project.id(), contract.id(), chw.id(), "CHW-QTY",
                "Measured CHW quantity", "QUANTITY_RATE", "m",
                new BigDecimal("300"), new BigDecimal("400"), new BigDecimal("120000"), null);
        assertThrows(ResponseStatusException.class, () -> commercialService.createValuation(
                contractorUser.id(), project.id(), contract.id(), quantityItem.id(), "VAL-QTY-001",
                "DOCUMENT_REVISION", "Not enough to prove accepted quantity", evidenceRevision.id(),
                new BigDecimal("120000"), BigDecimal.ZERO, BigDecimal.ZERO));

        // ---- Project drill-down: same truth, different dimensions, no cross-tier addition ----
        var drilldown = financialReadService.drilldown(contractorUser.id(), project.id(), contractor.id());
        assertEquals("ORGANIZATION_INTERNAL_COST", drilldown.perspective());
        assertEquals(1, drilldown.costStructures().size());
        assertTrue(drilldown.scopes().stream().anyMatch(scope -> scope.scopeId().equals(chw.id())));
        assertEquals(1, drilldown.contracts().size());
        money("20000", drilldown.costStructures().getFirst().nodes().stream()
                .filter(node -> node.node().id().equals(chwCost.id()))
                .findFirst().orElseThrow().financialSummary().actual());
        money("120000", drilldown.contracts().getFirst().commercialSummary().originalValue());
        assertTrue(drilldown.aggregationRule().contains("not added"));

        // ---- Cash flow is a derived view, not another ledger ----
        var contractorCash = financialReadService.cashFlow(
                contractorUser.id(), project.id(), contractor.id(),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31));
        var september = contractorCash.periods().stream()
                .filter(period -> period.month().toString().equals("2026-09"))
                .findFirst().orElseThrow();
        money("20000", september.postedInternalCost());
        money("0", september.actualCashIn());

        var october = contractorCash.periods().stream()
                .filter(period -> period.month().toString().equals("2026-10"))
                .findFirst().orElseThrow();
        money("30000", october.remainingCostForecast());
        money("18000", october.certifiedReceivable());
        money("100000", october.actualCashIn());
        money("100000", october.netActualCash());
        money("-12000", october.projectedFutureNetCash());
        assertTrue(contractorCash.accountingRule().contains("shown separately"));

        var clientCash = financialReadService.cashFlow(
                clientUser.id(), project.id(), client.id(),
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        var clientOctober = clientCash.periods().getFirst();
        money("18000", clientOctober.certifiedPayable());
        money("100000", clientOctober.actualCashOut());
        money("-100000", clientOctober.netActualCash());
        assertNotNull(trace.contract());
    }

    private static void money(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "Expected " + expected + " but got " + actual);
    }
}
