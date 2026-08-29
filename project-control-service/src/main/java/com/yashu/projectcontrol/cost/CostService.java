package com.yashu.projectcontrol.cost;

import com.yashu.projectcontrol.financial.FinancialAccessService;
import com.yashu.projectcontrol.organization.OrganizationService;
import com.yashu.projectcontrol.project.ProjectService;
import com.yashu.projectcontrol.scope.ScopeService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class CostService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100.0000");

    private final CostRepository repository;
    private final ProjectService projectService;
    private final ScopeService scopeService;
    private final OrganizationService organizationService;
    private final FinancialAccessService financialAccessService;

    public CostService(
            CostRepository repository,
            ProjectService projectService,
            ScopeService scopeService,
            OrganizationService organizationService,
            FinancialAccessService financialAccessService) {
        this.repository = repository;
        this.projectService = projectService;
        this.scopeService = scopeService;
        this.organizationService = organizationService;
        this.financialAccessService = financialAccessService;
    }

    @Transactional
    public StructureView createStructure(
            UUID actorUserId,
            UUID projectId,
            UUID owningOrganizationId,
            UUID contractId,
            String code,
            String name,
            String structureType) {
        var project = projectService.get(projectId);
        String type = code(structureType, "structureType");
        if (!List.of("INTERNAL_COST", "CLIENT_BUDGET", "CONTRACT_CBS", "OTHER").contains(type)) {
            throw bad("Unsupported cost structure type: " + type);
        }
        if (type.equals("INTERNAL_COST") && owningOrganizationId == null) {
            throw bad("INTERNAL_COST structure requires owningOrganizationId");
        }
        if (owningOrganizationId != null) {
            organizationService.requireExists(owningOrganizationId);
            financialAccessService.requirePrivateOrganizationCost(
                    actorUserId, projectId, owningOrganizationId, null, true);
        } else {
            financialAccessService.requireProjectManage(actorUserId, projectId);
        }
        if (contractId != null && !repository.contractBelongsToProject(contractId, projectId)) {
            throw bad("Contract does not belong to project");
        }
        if (type.equals("CONTRACT_CBS") && contractId == null) {
            throw bad("CONTRACT_CBS structure requires contractId");
        }
        return toView(repository.insertStructure(
                project.id(), owningOrganizationId, contractId,
                code(code, "code"), text(name, "name"), type));
    }

    @Transactional(readOnly = true)
    public List<StructureView> listStructures(
            UUID actorUserId, UUID projectId, UUID owningOrganizationId) {
        projectService.requireExists(projectId);
        financialAccessService.requirePrivateOrganizationCost(
                actorUserId, projectId, owningOrganizationId, null, false);
        return repository.listStructures(projectId, owningOrganizationId).stream()
                .map(CostService::toView)
                .toList();
    }

    @Transactional
    public NodeView createNode(
            UUID actorUserId,
            UUID projectId,
            UUID structureId,
            UUID parentNodeId,
            String code,
            String name,
            String category,
            Integer sortOrder) {
        var structure = requireStructure(projectId, structureId);
        financialAccessService.requirePrivateOrganizationCost(
                actorUserId, projectId, structure.owningOrganizationId(), null, true);
        if (parentNodeId != null) {
            var parent = requireNode(projectId, parentNodeId);
            if (!parent.structureId().equals(structureId)) {
                throw bad("Parent cost node must belong to the same cost structure");
            }
        }
        return toView(repository.insertNode(
                structureId, parentNodeId, code(code, "code"), text(name, "name"),
                optionalCode(category), sortOrder == null ? 0 : sortOrder));
    }

    @Transactional(readOnly = true)
    public List<NodeView> listNodes(UUID actorUserId, UUID projectId, UUID structureId) {
        var structure = requireStructure(projectId, structureId);
        financialAccessService.requirePrivateOrganizationCost(
                actorUserId, projectId, structure.owningOrganizationId(), null, false);
        return repository.listNodes(structureId).stream().map(CostService::toView).toList();
    }

    @Transactional
    public ScopeLinkView linkScope(
            UUID actorUserId,
            UUID projectId,
            UUID nodeId,
            UUID scopeId,
            BigDecimal allocationPercent,
            String relationshipType) {
        var node = requireNode(projectId, nodeId);
        scopeService.requireExistsInProject(projectId, scopeId);
        financialAccessService.requirePrivateOrganizationCost(
                actorUserId, projectId, node.owningOrganizationId(), scopeId, true);

        String relation = code(relationshipType, "relationshipType");
        BigDecimal allocation = moneyOrPercent(allocationPercent);
        if (relation.equals("ALLOCATION")) {
            if (allocation == null || allocation.signum() <= 0) {
                throw bad("ALLOCATION relationship requires allocationPercent greater than zero");
            }
            BigDecimal total = repository.allocatedPercent(nodeId).add(allocation);
            if (total.compareTo(ONE_HUNDRED) > 0) {
                throw bad("Total ALLOCATION percentage for a cost node cannot exceed 100");
            }
        }
        if (allocation != null && allocation.compareTo(ONE_HUNDRED) > 0) {
            throw bad("allocationPercent cannot exceed 100");
        }
        return toView(repository.insertScopeLink(nodeId, scopeId, allocation, relation));
    }

    @Transactional(readOnly = true)
    public List<ScopeLinkView> listScopeLinks(UUID actorUserId, UUID projectId, UUID nodeId) {
        var node = requireNode(projectId, nodeId);
        financialAccessService.requirePrivateOrganizationCost(
                actorUserId, projectId, node.owningOrganizationId(), null, false);
        return repository.listScopeLinks(nodeId).stream().map(CostService::toView).toList();
    }

    @Transactional
    public BudgetView createBudgetVersion(
            UUID actorUserId,
            UUID projectId,
            UUID structureId,
            String baselineType,
            String currency) {
        var structure = requireStructure(projectId, structureId);
        financialAccessService.requirePrivateOrganizationCost(
                actorUserId, projectId, structure.owningOrganizationId(), null, true);
        String baseline = code(baselineType, "baselineType");
        if (!List.of("ORIGINAL", "REVISED", "FORECAST").contains(baseline)) {
            throw bad("Unsupported budget baselineType: " + baseline);
        }
        String normalizedCurrency = projectCurrency(projectId, currency);
        int versionNumber = repository.nextBudgetVersion(structureId);
        return toView(repository.insertBudgetVersion(
                projectId, structure.owningOrganizationId(), structureId,
                versionNumber, baseline, normalizedCurrency, actorUserId));
    }

    @Transactional
    public BudgetLineView addBudgetLine(
            UUID actorUserId,
            UUID projectId,
            UUID budgetVersionId,
            UUID costNodeId,
            UUID scopeId,
            BigDecimal amount,
            String notes) {
        var budget = requireBudget(projectId, budgetVersionId);
        financialAccessService.requirePrivateOrganizationCost(
                actorUserId, projectId, budget.owningOrganizationId(), scopeId, true);
        if (!budget.status().equals("DRAFT")) {
            throw conflict("Budget lines can be changed only while budget version is DRAFT");
        }
        var node = requireNode(projectId, costNodeId);
        if (!node.structureId().equals(budget.structureId())) {
            throw bad("Budget line cost node must belong to the budget cost structure");
        }
        if (scopeId != null) {
            scopeService.requireExistsInProject(projectId, scopeId);
        }
        BigDecimal normalizedAmount = positiveOrZero(amount, "amount");
        return toView(repository.insertBudgetLine(
                budgetVersionId, costNodeId, scopeId, normalizedAmount, optional(notes)));
    }

    @Transactional(readOnly = true)
    public BudgetView getBudget(UUID actorUserId, UUID projectId, UUID budgetVersionId) {
        var budget = requireBudget(projectId, budgetVersionId);
        financialAccessService.requirePrivateOrganizationCost(
                actorUserId, projectId, budget.owningOrganizationId(), null, false);
        return toView(budget);
    }

    @Transactional(readOnly = true)
    public List<BudgetLineView> listBudgetLines(
            UUID actorUserId, UUID projectId, UUID budgetVersionId) {
        var budget = requireBudget(projectId, budgetVersionId);
        financialAccessService.requirePrivateOrganizationCost(
                actorUserId, projectId, budget.owningOrganizationId(), null, false);
        return repository.listBudgetLines(budgetVersionId).stream().map(CostService::toView).toList();
    }

    @Transactional
    public BudgetView submitBudget(
            UUID actorUserId, UUID projectId, UUID budgetVersionId, long expectedVersion) {
        var budget = requireBudget(projectId, budgetVersionId);
        financialAccessService.requirePrivateOrganizationCost(
                actorUserId, projectId, budget.owningOrganizationId(), null, true);
        if (repository.listBudgetLines(budgetVersionId).isEmpty()) {
            throw bad("A budget version cannot be submitted without budget lines");
        }
        if (repository.transitionBudget(
                budgetVersionId, expectedVersion, "DRAFT", "SUBMITTED", actorUserId) != 1) {
            throw conflict("Budget version is stale or is not in DRAFT status");
        }
        return toView(repository.requireBudget(budgetVersionId));
    }

    @Transactional
    public BudgetView approveBudget(
            UUID actorUserId, UUID projectId, UUID budgetVersionId, long expectedVersion) {
        var budget = requireBudget(projectId, budgetVersionId);
        financialAccessService.requirePrivateOrganizationCost(
                actorUserId, projectId, budget.owningOrganizationId(), null, true);
        if (repository.transitionBudget(
                budgetVersionId, expectedVersion, "SUBMITTED", "APPROVED", actorUserId) != 1) {
            throw conflict("Budget version is stale or is not in SUBMITTED status");
        }
        if (!budget.baselineType().equals("FORECAST")) {
            repository.supersedePreviousApprovedBudget(budget.structureId(), budgetVersionId);
        }
        return toView(repository.requireBudget(budgetVersionId));
    }

    @Transactional(readOnly = true)
    public CostSummary summary(
            UUID actorUserId,
            UUID projectId,
            UUID costNodeId) {
        var node = requireNode(projectId, costNodeId);
        financialAccessService.requirePrivateOrganizationCost(
                actorUserId, projectId, node.owningOrganizationId(), null, false);
        return calculateSummary(node);
    }

    @Transactional
    public BudgetDecision budgetCheck(
            UUID actorUserId,
            UUID projectId,
            UUID owningOrganizationId,
            UUID scopeId,
            UUID costNodeId,
            BigDecimal proposedExposure,
            String requestResourceReference) {
        return budgetCheckInternal(
                actorUserId, projectId, owningOrganizationId, scopeId, costNodeId,
                proposedExposure, requestResourceReference, false);
    }

    @Transactional
    public CommitmentView createCommitment(
            UUID actorUserId,
            UUID projectId,
            UUID owningOrganizationId,
            UUID counterpartyOrganizationId,
            UUID contractId,
            UUID scopeId,
            UUID costNodeId,
            String reference,
            BigDecimal amount,
            String currency,
            Instant committedAt,
            UUID sourceDocumentRevisionId) {
        var node = requireNode(projectId, costNodeId);
        if (!same(node.owningOrganizationId(), owningOrganizationId)) {
            throw bad("Commitment owningOrganizationId must match the cost structure owner");
        }
        financialAccessService.requirePrivateOrganizationCost(
                actorUserId, projectId, owningOrganizationId, scopeId, true);
        validateScopeAndCapability(projectId, scopeId);
        if (counterpartyOrganizationId != null) {
            organizationService.requireExists(counterpartyOrganizationId);
        }
        if (contractId != null && !repository.contractBelongsToProject(contractId, projectId)) {
            throw bad("Contract does not belong to project");
        }
        validateEvidence(projectId, sourceDocumentRevisionId);
        BigDecimal normalizedAmount = positive(amount, "amount");
        String normalizedCurrency = projectCurrency(projectId, currency);

        // Authoritative cost-incurring command executes the gate inside this transaction.
        BudgetDecision decision = budgetCheckInternal(
                actorUserId, projectId, owningOrganizationId, scopeId, costNodeId,
                normalizedAmount, "COMMITMENT:" + text(reference, "reference"), true);
        if (!decision.decision().equals("ALLOW")) {
            throw conflict("Commitment blocked by deterministic budget control: " + decision.reason());
        }

        return toView(repository.insertCommitment(
                projectId, owningOrganizationId, counterpartyOrganizationId, contractId,
                scopeId, costNodeId, code(reference, "reference"), normalizedAmount,
                normalizedCurrency, committedAt == null ? Instant.now() : committedAt,
                sourceDocumentRevisionId, actorUserId));
    }

    @Transactional
    public ActualCostView postActualCost(
            UUID actorUserId,
            UUID projectId,
            UUID owningOrganizationId,
            UUID scopeId,
            UUID costNodeId,
            UUID commitmentId,
            String sourceType,
            String sourceReference,
            UUID counterpartyOrganizationId,
            BigDecimal amount,
            String currency,
            LocalDate accountingDate,
            UUID sourceDocumentRevisionId) {
        var node = requireNode(projectId, costNodeId);
        if (!same(node.owningOrganizationId(), owningOrganizationId)) {
            throw bad("Actual cost owningOrganizationId must match the cost structure owner");
        }
        financialAccessService.requirePrivateOrganizationCost(
                actorUserId, projectId, owningOrganizationId, scopeId, true);
        if (scopeId != null) scopeService.requireExistsInProject(projectId, scopeId);
        if (counterpartyOrganizationId != null) organizationService.requireExists(counterpartyOrganizationId);
        if (commitmentId != null) {
            var commitment = repository.findCommitment(commitmentId)
                    .orElseThrow(() -> bad("Commitment not found"));
            if (!commitment.projectId().equals(projectId)
                    || !commitment.costNodeId().equals(costNodeId)
                    || !commitment.owningOrganizationId().equals(owningOrganizationId)) {
                throw bad("Actual cost commitment must belong to the same project, organization and cost node");
            }
        }
        validateEvidence(projectId, sourceDocumentRevisionId);
        return toView(repository.insertActual(
                projectId, owningOrganizationId, scopeId, costNodeId, commitmentId,
                code(sourceType, "sourceType"), text(sourceReference, "sourceReference"),
                counterpartyOrganizationId, positive(amount, "amount"),
                projectCurrency(projectId, currency),
                accountingDate == null ? LocalDate.now() : accountingDate,
                sourceDocumentRevisionId, actorUserId));
    }

    @Transactional
    public ForecastView setForecast(
            UUID actorUserId,
            UUID projectId,
            UUID owningOrganizationId,
            UUID scopeId,
            UUID costNodeId,
            LocalDate forecastPeriod,
            BigDecimal remainingForecastAmount,
            String currency,
            String basis,
            UUID sourceDocumentRevisionId) {
        var node = requireNode(projectId, costNodeId);
        if (!same(node.owningOrganizationId(), owningOrganizationId)) {
            throw bad("Forecast owningOrganizationId must match the cost structure owner");
        }
        financialAccessService.requirePrivateOrganizationCost(
                actorUserId, projectId, owningOrganizationId, scopeId, true);
        if (scopeId != null) scopeService.requireExistsInProject(projectId, scopeId);
        validateEvidence(projectId, sourceDocumentRevisionId);
        LocalDate period = forecastPeriod == null ? LocalDate.now().withDayOfMonth(1) : forecastPeriod.withDayOfMonth(1);
        BigDecimal amount = positiveOrZero(remainingForecastAmount, "remainingForecastAmount");
        repository.supersedeForecast(projectId, owningOrganizationId, costNodeId, period);
        return toView(repository.insertForecast(
                projectId, owningOrganizationId, scopeId, costNodeId, period, amount,
                projectCurrency(projectId, currency), optional(basis), sourceDocumentRevisionId, actorUserId));
    }

    @Transactional(readOnly = true)
    public ScopeCostSummary scopeSummary(
            UUID actorUserId,
            UUID projectId,
            UUID owningOrganizationId,
            UUID scopeId) {
        scopeService.requireExistsInProject(projectId, scopeId);
        financialAccessService.requirePrivateOrganizationCost(
                actorUserId, projectId, owningOrganizationId, scopeId, false);
        BigDecimal actual = repository.directScopeActual(projectId, owningOrganizationId, scopeId);
        BigDecimal openCommitment = repository.directScopeOpenCommitment(projectId, owningOrganizationId, scopeId);
        BigDecimal forecast = repository.directScopeForecast(projectId, owningOrganizationId, scopeId);
        return new ScopeCostSummary(scopeId, actual, openCommitment, forecast, actual.add(forecast));
    }

    private BudgetDecision budgetCheckInternal(
            UUID actorUserId,
            UUID projectId,
            UUID owningOrganizationId,
            UUID scopeId,
            UUID costNodeId,
            BigDecimal proposedExposure,
            String requestResourceReference,
            boolean authoritative) {
        var node = requireNode(projectId, costNodeId);
        if (!same(node.owningOrganizationId(), owningOrganizationId)) {
            throw bad("Budget check organization must match the cost structure owner");
        }
        financialAccessService.requirePrivateOrganizationCost(
                actorUserId, projectId, owningOrganizationId, scopeId, authoritative);
        if (scopeId != null) {
            validateScopeAndCapability(projectId, scopeId);
        }
        BigDecimal proposed = positive(proposedExposure, "proposedExposure");
        if (authoritative) repository.lockNode(costNodeId);

        CostSummary summary = calculateSummary(node);
        BigDecimal availableAfter = summary.availableBudget().subtract(proposed);
        String decision = availableAfter.signum() >= 0 ? "ALLOW" : "BLOCK";
        String reason = decision.equals("ALLOW")
                ? "Proposed exposure remains within current available budget"
                : "Proposed exposure exceeds current available budget by " + availableAfter.abs().toPlainString();
        UUID decisionId = repository.insertBudgetDecision(
                projectId, scopeId, owningOrganizationId, costNodeId,
                optional(requestResourceReference), summary.currentBudget(), summary.actual(),
                summary.openCommitment(), summary.remainingForecast(), proposed,
                summary.availableBudget(), availableAfter, decision, reason, actorUserId);
        return new BudgetDecision(
                decisionId, costNodeId, summary.currentBudget(), summary.actual(),
                summary.openCommitment(), summary.remainingForecast(), proposed,
                summary.availableBudget(), availableAfter, decision, reason,
                authoritative ? "AUTHORITATIVE" : "PREVIEW");
    }

    private CostSummary calculateSummary(CostRepository.NodeRow node) {
        BigDecimal original = repository.originalBudget(node.structureId(), node.id());
        BigDecimal current = repository.currentBudget(node.structureId(), node.id());
        BigDecimal committed = repository.committed(node.id());
        BigDecimal actual = repository.actual(node.id());
        BigDecimal openCommitment = repository.openCommitment(node.id());
        BigDecimal forecast = repository.remainingForecast(node.id());
        BigDecimal eac = actual.add(forecast);
        BigDecimal vac = current.subtract(eac);
        // First v2.1 slice has no reservation/accrual write model. Exposure therefore uses Actual + Open Commitment.
        BigDecimal exposure = actual.add(openCommitment);
        BigDecimal available = current.subtract(exposure);
        return new CostSummary(
                node.id(), node.structureId(), node.owningOrganizationId(), original, current,
                committed, actual, openCommitment, forecast, eac, vac, exposure, available);
    }

    private void validateScopeAndCapability(UUID projectId, UUID scopeId) {
        if (scopeId == null) return;
        scopeService.requireExistsInProject(projectId, scopeId);
        scopeService.requireEnabledCapability(projectId, scopeId, "BUDGET_CONTROL");
    }

    private void validateEvidence(UUID projectId, UUID revisionId) {
        if (!repository.revisionBelongsToProject(revisionId, projectId)) {
            throw bad("sourceDocumentRevisionId must belong to the same project");
        }
    }

    private CostRepository.StructureRow requireStructure(UUID projectId, UUID structureId) {
        var structure = repository.findStructure(structureId)
                .orElseThrow(() -> notFound("Cost structure not found: " + structureId));
        if (!structure.projectId().equals(projectId)) {
            throw notFound("Cost structure not found in project: " + structureId);
        }
        return structure;
    }

    private CostRepository.NodeRow requireNode(UUID projectId, UUID nodeId) {
        var node = repository.findNode(nodeId)
                .orElseThrow(() -> notFound("Cost node not found: " + nodeId));
        if (!node.projectId().equals(projectId)) {
            throw notFound("Cost node not found in project: " + nodeId);
        }
        return node;
    }

    private CostRepository.BudgetVersionRow requireBudget(UUID projectId, UUID budgetId) {
        var budget = repository.findBudget(budgetId)
                .orElseThrow(() -> notFound("Budget version not found: " + budgetId));
        if (!budget.projectId().equals(projectId)) {
            throw notFound("Budget version not found in project: " + budgetId);
        }
        return budget;
    }

    private String projectCurrency(UUID projectId, String requested) {
        String projectCurrency = projectService.get(projectId).currency();
        String normalized = requested == null || requested.isBlank()
                ? projectCurrency
                : requested.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals(projectCurrency)) {
            throw bad("Financial foundation currently requires project currency " + projectCurrency
                    + "; FX conversion must be explicit before mixed-currency rollups are allowed");
        }
        return normalized;
    }

    private static BigDecimal positive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) throw bad(field + " must be greater than zero");
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal positiveOrZero(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) throw bad(field + " cannot be negative");
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal moneyOrPercent(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    private static String code(String value, String field) {
        return text(value, field).toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String optionalCode(String value) {
        String normalized = optional(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) throw bad(field + " is required");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean same(UUID left, UUID right) {
        return left == null ? right == null : left.equals(right);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static StructureView toView(CostRepository.StructureRow row) {
        return new StructureView(row.id(), row.projectId(), row.owningOrganizationId(), row.contractId(),
                row.code(), row.name(), row.structureType(), row.status(), row.version());
    }

    private static NodeView toView(CostRepository.NodeRow row) {
        return new NodeView(row.id(), row.structureId(), row.parentNodeId(), row.code(), row.name(),
                row.category(), row.sortOrder(), row.status());
    }

    private static ScopeLinkView toView(CostRepository.ScopeLinkRow row) {
        return new ScopeLinkView(row.id(), row.costNodeId(), row.scopeId(), row.allocationPercent(), row.relationshipType());
    }

    private static BudgetView toView(CostRepository.BudgetVersionRow row) {
        return new BudgetView(row.id(), row.projectId(), row.owningOrganizationId(), row.structureId(),
                row.versionNumber(), row.status(), row.baselineType(), row.currency(), row.createdBy(),
                row.submittedBy(), row.approvedBy(), row.submittedAt(), row.approvedAt(), row.version());
    }

    private static BudgetLineView toView(CostRepository.BudgetLineRow row) {
        return new BudgetLineView(row.id(), row.budgetVersionId(), row.costNodeId(), row.scopeId(), row.amount(), row.notes());
    }

    private static CommitmentView toView(CostRepository.CommitmentRow row) {
        return new CommitmentView(row.id(), row.projectId(), row.owningOrganizationId(), row.counterpartyOrganizationId(),
                row.contractId(), row.scopeId(), row.costNodeId(), row.reference(), row.amount(), row.currency(),
                row.status(), row.committedAt(), row.sourceDocumentRevisionId(), row.version());
    }

    private static ActualCostView toView(CostRepository.ActualRow row) {
        return new ActualCostView(row.id(), row.projectId(), row.owningOrganizationId(), row.scopeId(),
                row.costNodeId(), row.commitmentId(), row.sourceType(), row.sourceReference(), row.amount(),
                row.currency(), row.accountingDate(), row.status(), row.sourceDocumentRevisionId());
    }

    private static ForecastView toView(CostRepository.ForecastRow row) {
        return new ForecastView(row.id(), row.projectId(), row.owningOrganizationId(), row.scopeId(), row.costNodeId(),
                row.forecastPeriod(), row.remainingForecastAmount(), row.currency(), row.basis(), row.status(),
                row.sourceDocumentRevisionId(), row.version());
    }

    public record StructureView(UUID id, UUID projectId, UUID owningOrganizationId, UUID contractId,
                                String code, String name, String structureType, String status, long version) {}
    public record NodeView(UUID id, UUID structureId, UUID parentNodeId, String code, String name,
                           String category, int sortOrder, String status) {}
    public record ScopeLinkView(UUID id, UUID costNodeId, UUID scopeId, BigDecimal allocationPercent,
                                String relationshipType) {}
    public record BudgetView(UUID id, UUID projectId, UUID owningOrganizationId, UUID costStructureId,
                             int versionNumber, String status, String baselineType, String currency,
                             UUID createdBy, UUID submittedBy, UUID approvedBy,
                             Instant submittedAt, Instant approvedAt, long version) {}
    public record BudgetLineView(UUID id, UUID budgetVersionId, UUID costNodeId, UUID scopeId,
                                 BigDecimal amount, String notes) {}
    public record CommitmentView(UUID id, UUID projectId, UUID owningOrganizationId,
                                 UUID counterpartyOrganizationId, UUID contractId, UUID scopeId, UUID costNodeId,
                                 String reference, BigDecimal amount, String currency, String status,
                                 Instant committedAt, UUID sourceDocumentRevisionId, long version) {}
    public record ActualCostView(UUID id, UUID projectId, UUID owningOrganizationId, UUID scopeId,
                                 UUID costNodeId, UUID commitmentId, String sourceType, String sourceReference,
                                 BigDecimal amount, String currency, LocalDate accountingDate, String status,
                                 UUID sourceDocumentRevisionId) {}
    public record ForecastView(UUID id, UUID projectId, UUID owningOrganizationId, UUID scopeId, UUID costNodeId,
                               LocalDate forecastPeriod, BigDecimal remainingForecastAmount, String currency,
                               String basis, String status, UUID sourceDocumentRevisionId, long version) {}
    public record CostSummary(UUID costNodeId, UUID costStructureId, UUID owningOrganizationId,
                              BigDecimal originalBudget, BigDecimal currentBudget, BigDecimal committed,
                              BigDecimal actual, BigDecimal openCommitment, BigDecimal remainingForecast,
                              BigDecimal eac, BigDecimal vac, BigDecimal budgetExposure, BigDecimal availableBudget) {}
    public record ScopeCostSummary(UUID scopeId, BigDecimal actual, BigDecimal openCommitment,
                                   BigDecimal remainingForecast, BigDecimal eac) {}
    public record BudgetDecision(UUID id, UUID costNodeId, BigDecimal currentBudget, BigDecimal actual,
                                 BigDecimal openCommitment, BigDecimal remainingForecast,
                                 BigDecimal proposedExposure, BigDecimal availableBefore,
                                 BigDecimal availableAfter, String decision, String reason, String mode) {}
}
