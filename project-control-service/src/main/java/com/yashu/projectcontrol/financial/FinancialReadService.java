package com.yashu.projectcontrol.financial;

import com.yashu.projectcontrol.access.ProjectAccessService;
import com.yashu.projectcontrol.commercial.CommercialService;
import com.yashu.projectcontrol.cost.CostService;
import com.yashu.projectcontrol.project.ProjectService;
import com.yashu.projectcontrol.scope.ScopeService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.SCOPE_VIEW;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessOutcome.ALLOW;

@Service
public class FinancialReadService {

    private final FinancialReadRepository repository;
    private final FinancialAccessService financialAccessService;
    private final ProjectAccessService projectAccessService;
    private final ProjectService projectService;
    private final ScopeService scopeService;
    private final CostService costService;
    private final CommercialService commercialService;

    public FinancialReadService(
            FinancialReadRepository repository,
            FinancialAccessService financialAccessService,
            ProjectAccessService projectAccessService,
            ProjectService projectService,
            ScopeService scopeService,
            CostService costService,
            CommercialService commercialService) {
        this.repository = repository;
        this.financialAccessService = financialAccessService;
        this.projectAccessService = projectAccessService;
        this.projectService = projectService;
        this.scopeService = scopeService;
        this.costService = costService;
        this.commercialService = commercialService;
    }

    @Transactional(readOnly = true)
    public ProjectFinancialDrilldown drilldown(
            UUID actorUserId,
            UUID projectId,
            UUID owningOrganizationId) {
        if (owningOrganizationId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "owningOrganizationId is required so the financial perspective is explicit");
        }
        financialAccessService.requirePrivateOrganizationCost(
                actorUserId, projectId, owningOrganizationId, null, false);
        var project = projectService.get(projectId);

        List<ScopeFinancialView> scopeViews = scopeService.listByProject(projectId).stream()
                .filter(scope -> projectAccessService.decide(
                        actorUserId, SCOPE_VIEW, projectId, scope.id()).outcome() == ALLOW)
                .map(scope -> new ScopeFinancialView(
                        scope.id(), scope.parentScopeId(), scope.scopeType(), scope.code(), scope.name(),
                        costService.scopeSummary(actorUserId, projectId, owningOrganizationId, scope.id())))
                .toList();

        List<CbsStructureView> cbs = costService.listStructures(actorUserId, projectId, owningOrganizationId).stream()
                .map(structure -> new CbsStructureView(
                        structure,
                        costService.listNodes(actorUserId, projectId, structure.id()).stream()
                                .map(node -> new CbsNodeView(
                                        node,
                                        costService.summary(actorUserId, projectId, node.id()),
                                        costService.listScopeLinks(actorUserId, projectId, node.id())))
                                .toList()))
                .toList();

        List<ContractPerspectiveView> contracts = commercialService.listContracts(actorUserId, projectId).stream()
                .filter(contract -> contract.payerOrganizationId().equals(owningOrganizationId)
                        || contract.payeeOrganizationId().equals(owningOrganizationId))
                .map(contract -> new ContractPerspectiveView(
                        contract,
                        contract.payerOrganizationId().equals(owningOrganizationId) ? "PAYER" : "PAYEE",
                        commercialService.contractSummary(actorUserId, projectId, contract.id())))
                .toList();

        return new ProjectFinancialDrilldown(
                projectId,
                project.code(),
                project.name(),
                project.currency(),
                owningOrganizationId,
                "ORGANIZATION_INTERNAL_COST",
                scopeViews,
                cbs,
                contracts,
                "Scope and CBS are separate dimensions. Contract values are a separate commercial perspective and are not added to internal cost totals.");
    }

    @Transactional(readOnly = true)
    public CashFlowView cashFlow(
            UUID actorUserId,
            UUID projectId,
            UUID organizationId,
            LocalDate from,
            LocalDate to) {
        if (organizationId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "organizationId is required");
        }
        if (from == null || to == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from and to dates are required");
        }
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to cannot be before from");
        }
        financialAccessService.requirePrivateOrganizationCost(
                actorUserId, projectId, organizationId, null, false);
        var project = projectService.get(projectId);

        YearMonth first = YearMonth.from(from);
        YearMonth last = YearMonth.from(to);
        Map<YearMonth, MutableCashPeriod> periods = new LinkedHashMap<>();
        YearMonth cursor = first;
        while (!cursor.isAfter(last)) {
            periods.put(cursor, new MutableCashPeriod(cursor));
            cursor = cursor.plusMonths(1);
        }

        for (var fact : repository.cashFacts(projectId, organizationId, from, to)) {
            MutableCashPeriod period = periods.get(YearMonth.from(fact.date()));
            if (period == null) continue;
            period.add(fact.category(), fact.amount());
        }

        List<CashFlowPeriod> result = new ArrayList<>();
        for (MutableCashPeriod period : periods.values()) {
            result.add(period.freeze());
        }
        return new CashFlowView(
                projectId,
                organizationId,
                project.currency(),
                from,
                to,
                result,
                "Derived view only: posted internal cost is accrual/accounting truth, while actual cash in/out comes only from Payment records. These values are intentionally shown separately.");
    }

    public record ProjectFinancialDrilldown(
            UUID projectId,
            String projectCode,
            String projectName,
            String currency,
            UUID owningOrganizationId,
            String perspective,
            List<ScopeFinancialView> scopes,
            List<CbsStructureView> costStructures,
            List<ContractPerspectiveView> contracts,
            String aggregationRule) {}

    public record ScopeFinancialView(
            UUID scopeId,
            UUID parentScopeId,
            String scopeType,
            String scopeCode,
            String scopeName,
            CostService.ScopeCostSummary directLedgerSummary) {}

    public record CbsStructureView(
            CostService.StructureView structure,
            List<CbsNodeView> nodes) {}

    public record CbsNodeView(
            CostService.NodeView node,
            CostService.CostSummary financialSummary,
            List<CostService.ScopeLinkView> scopeLinks) {}

    public record ContractPerspectiveView(
            CommercialService.ContractView contract,
            String organizationRelationship,
            CommercialService.ContractSummary commercialSummary) {}

    public record CashFlowView(
            UUID projectId,
            UUID organizationId,
            String currency,
            LocalDate from,
            LocalDate to,
            List<CashFlowPeriod> periods,
            String accountingRule) {}

    public record CashFlowPeriod(
            YearMonth month,
            BigDecimal postedInternalCost,
            BigDecimal remainingCostForecast,
            BigDecimal certifiedReceivable,
            BigDecimal certifiedPayable,
            BigDecimal actualCashIn,
            BigDecimal actualCashOut,
            BigDecimal netActualCash,
            BigDecimal projectedFutureNetCash) {}

    private static final class MutableCashPeriod {
        private final YearMonth month;
        private BigDecimal postedCost = BigDecimal.ZERO;
        private BigDecimal forecast = BigDecimal.ZERO;
        private BigDecimal receivable = BigDecimal.ZERO;
        private BigDecimal payable = BigDecimal.ZERO;
        private BigDecimal cashIn = BigDecimal.ZERO;
        private BigDecimal cashOut = BigDecimal.ZERO;

        private MutableCashPeriod(YearMonth month) {
            this.month = month;
        }

        private void add(String category, BigDecimal amount) {
            switch (category) {
                case "POSTED_INTERNAL_COST" -> postedCost = postedCost.add(amount);
                case "REMAINING_COST_FORECAST" -> forecast = forecast.add(amount);
                case "CERTIFIED_RECEIVABLE" -> receivable = receivable.add(amount);
                case "CERTIFIED_PAYABLE" -> payable = payable.add(amount);
                case "ACTUAL_CASH_IN" -> cashIn = cashIn.add(amount);
                case "ACTUAL_CASH_OUT" -> cashOut = cashOut.add(amount);
                default -> throw new IllegalStateException("Unknown cash-flow category: " + category);
            }
        }

        private CashFlowPeriod freeze() {
            return new CashFlowPeriod(
                    month,
                    postedCost,
                    forecast,
                    receivable,
                    payable,
                    cashIn,
                    cashOut,
                    cashIn.subtract(cashOut),
                    receivable.subtract(payable).subtract(forecast));
        }
    }
}
