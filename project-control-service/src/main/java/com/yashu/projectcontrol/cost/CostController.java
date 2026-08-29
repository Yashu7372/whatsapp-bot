package com.yashu.projectcontrol.cost;

import com.yashu.projectcontrol.access.ProjectControlPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class CostController {

    private final CostService service;

    public CostController(CostService service) {
        this.service = service;
    }

    @PostMapping("/cost-structures")
    public ResponseEntity<CostService.StructureView> createStructure(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody CreateStructureRequest request) {
        return ResponseEntity.ok(service.createStructure(
                principal.userId(), projectId, request.owningOrganizationId(), request.contractId(),
                request.code(), request.name(), request.structureType()));
    }

    @GetMapping("/cost-structures")
    public ResponseEntity<List<CostService.StructureView>> listStructures(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestParam(required = false) UUID owningOrganizationId) {
        return ResponseEntity.ok(service.listStructures(principal.userId(), projectId, owningOrganizationId));
    }

    @PostMapping("/cost-structures/{structureId}/nodes")
    public ResponseEntity<CostService.NodeView> createNode(
            @PathVariable UUID projectId,
            @PathVariable UUID structureId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody CreateNodeRequest request) {
        return ResponseEntity.ok(service.createNode(
                principal.userId(), projectId, structureId, request.parentNodeId(),
                request.code(), request.name(), request.category(), request.sortOrder()));
    }

    @GetMapping("/cost-structures/{structureId}/nodes")
    public ResponseEntity<List<CostService.NodeView>> listNodes(
            @PathVariable UUID projectId,
            @PathVariable UUID structureId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        return ResponseEntity.ok(service.listNodes(principal.userId(), projectId, structureId));
    }

    @PostMapping("/cost-nodes/{nodeId}/scope-links")
    public ResponseEntity<CostService.ScopeLinkView> linkScope(
            @PathVariable UUID projectId,
            @PathVariable UUID nodeId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody LinkScopeRequest request) {
        return ResponseEntity.ok(service.linkScope(
                principal.userId(), projectId, nodeId, request.scopeId(),
                request.allocationPercent(), request.relationshipType()));
    }

    @GetMapping("/cost-nodes/{nodeId}/scope-links")
    public ResponseEntity<List<CostService.ScopeLinkView>> scopeLinks(
            @PathVariable UUID projectId,
            @PathVariable UUID nodeId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        return ResponseEntity.ok(service.listScopeLinks(principal.userId(), projectId, nodeId));
    }

    @PostMapping("/budgets/versions")
    public ResponseEntity<CostService.BudgetView> createBudget(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody CreateBudgetRequest request) {
        return ResponseEntity.ok(service.createBudgetVersion(
                principal.userId(), projectId, request.costStructureId(),
                request.baselineType(), request.currency()));
    }

    @GetMapping("/budgets/{versionId}")
    public ResponseEntity<CostService.BudgetView> getBudget(
            @PathVariable UUID projectId,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        return ResponseEntity.ok(service.getBudget(principal.userId(), projectId, versionId));
    }

    @PostMapping("/budgets/{versionId}/lines")
    public ResponseEntity<CostService.BudgetLineView> addBudgetLine(
            @PathVariable UUID projectId,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody AddBudgetLineRequest request) {
        return ResponseEntity.ok(service.addBudgetLine(
                principal.userId(), projectId, versionId, request.costNodeId(),
                request.scopeId(), request.amount(), request.notes()));
    }

    @GetMapping("/budgets/{versionId}/lines")
    public ResponseEntity<List<CostService.BudgetLineView>> budgetLines(
            @PathVariable UUID projectId,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        return ResponseEntity.ok(service.listBudgetLines(principal.userId(), projectId, versionId));
    }

    @PostMapping("/budgets/{versionId}/submit")
    public ResponseEntity<CostService.BudgetView> submitBudget(
            @PathVariable UUID projectId,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody VersionRequest request) {
        return ResponseEntity.ok(service.submitBudget(
                principal.userId(), projectId, versionId, request.version()));
    }

    @PostMapping("/budgets/{versionId}/approve")
    public ResponseEntity<CostService.BudgetView> approveBudget(
            @PathVariable UUID projectId,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody VersionRequest request) {
        return ResponseEntity.ok(service.approveBudget(
                principal.userId(), projectId, versionId, request.version()));
    }

    @GetMapping("/cost-summary")
    public ResponseEntity<CostService.CostSummary> summary(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestParam UUID costNodeId) {
        return ResponseEntity.ok(service.summary(principal.userId(), projectId, costNodeId));
    }

    @GetMapping("/scopes/{scopeId}/cost-summary")
    public ResponseEntity<CostService.ScopeCostSummary> scopeSummary(
            @PathVariable UUID projectId,
            @PathVariable UUID scopeId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestParam UUID owningOrganizationId) {
        return ResponseEntity.ok(service.scopeSummary(
                principal.userId(), projectId, owningOrganizationId, scopeId));
    }

    @PostMapping("/budget-control/check")
    public ResponseEntity<CostService.BudgetDecision> budgetCheck(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody BudgetCheckRequest request) {
        return ResponseEntity.ok(service.budgetCheck(
                principal.userId(), projectId, request.owningOrganizationId(), request.scopeId(),
                request.costNodeId(), request.proposedExposure(), request.requestResourceReference()));
    }

    @PostMapping("/commitments")
    public ResponseEntity<CostService.CommitmentView> createCommitment(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody CreateCommitmentRequest request) {
        return ResponseEntity.ok(service.createCommitment(
                principal.userId(), projectId, request.owningOrganizationId(), request.counterpartyOrganizationId(),
                request.contractId(), request.scopeId(), request.costNodeId(), request.reference(), request.amount(),
                request.currency(), request.committedAt(), request.sourceDocumentRevisionId()));
    }

    @PostMapping("/actual-costs")
    public ResponseEntity<CostService.ActualCostView> postActual(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody PostActualCostRequest request) {
        return ResponseEntity.ok(service.postActualCost(
                principal.userId(), projectId, request.owningOrganizationId(), request.scopeId(),
                request.costNodeId(), request.commitmentId(), request.sourceType(), request.sourceReference(),
                request.counterpartyOrganizationId(), request.amount(), request.currency(), request.accountingDate(),
                request.sourceDocumentRevisionId()));
    }

    @PostMapping("/forecasts")
    public ResponseEntity<CostService.ForecastView> setForecast(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody SetForecastRequest request) {
        return ResponseEntity.ok(service.setForecast(
                principal.userId(), projectId, request.owningOrganizationId(), request.scopeId(),
                request.costNodeId(), request.forecastPeriod(), request.remainingForecastAmount(), request.currency(),
                request.basis(), request.sourceDocumentRevisionId()));
    }

    public record CreateStructureRequest(
            UUID owningOrganizationId, UUID contractId, String code, String name, String structureType) {}
    public record CreateNodeRequest(
            UUID parentNodeId, String code, String name, String category, Integer sortOrder) {}
    public record LinkScopeRequest(UUID scopeId, BigDecimal allocationPercent, String relationshipType) {}
    public record CreateBudgetRequest(UUID costStructureId, String baselineType, String currency) {}
    public record AddBudgetLineRequest(UUID costNodeId, UUID scopeId, BigDecimal amount, String notes) {}
    public record VersionRequest(long version) {}
    public record BudgetCheckRequest(
            UUID owningOrganizationId, UUID scopeId, UUID costNodeId,
            BigDecimal proposedExposure, String requestResourceReference) {}
    public record CreateCommitmentRequest(
            UUID owningOrganizationId, UUID counterpartyOrganizationId, UUID contractId,
            UUID scopeId, UUID costNodeId, String reference, BigDecimal amount, String currency,
            Instant committedAt, UUID sourceDocumentRevisionId) {}
    public record PostActualCostRequest(
            UUID owningOrganizationId, UUID scopeId, UUID costNodeId, UUID commitmentId,
            String sourceType, String sourceReference, UUID counterpartyOrganizationId,
            BigDecimal amount, String currency, LocalDate accountingDate, UUID sourceDocumentRevisionId) {}
    public record SetForecastRequest(
            UUID owningOrganizationId, UUID scopeId, UUID costNodeId, LocalDate forecastPeriod,
            BigDecimal remainingForecastAmount, String currency, String basis, UUID sourceDocumentRevisionId) {}
}
