package com.whatsappbot.projectcontrols;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.UserRole;
import com.whatsappbot.project.PartyRole;
import com.whatsappbot.project.ProjectAccessService;
import com.whatsappbot.project.ProjectEntity;
import com.whatsappbot.project.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectControlsService {
    private static final Set<String> COMMERCIAL_MODELS = Set.of("FIXED_FEE","TIME_BASED","MILESTONE","DELIVERABLE","UNIT_RATE","PERCENTAGE","HYBRID");

    private final ProjectControlsRepository repository;
    private final ProjectService projectService;
    private final ProjectAccessService accessService;

    @Transactional(readOnly = true)
    public ControlsSummary summary(UUID tenantId, UUID userId, UUID projectId) {
        ProjectEntity project = projectService.get(tenantId, userId, projectId);
        TenantUserEntity actor = accessService.requireActiveUser(tenantId, userId);
        boolean broad = canSeeWholeCommercialProject(tenantId, projectId, actor);
        UUID orgId = broad ? null : actor.getOrganizationId();

        ProjectControlsRepository.MoneyTotals contracts = repository.contractTotals(tenantId, projectId, orgId);
        ForecastView latestForecast = latestForecast(tenantId, projectId, orgId);

        BudgetTotals budget;
        BigDecimal visibleProjectContractValue;
        if (broad) {
            budget = repository.latestBudgetTotals(tenantId, projectId);
            visibleProjectContractValue = project.getContractValue() == null ? BigDecimal.ZERO : project.getContractValue();
        } else {
            BigDecimal ownContract = contracts.original().add(contracts.changes());
            BigDecimal ownEtc = latestForecast == null ? BigDecimal.ZERO : latestForecast.estimateToComplete();
            budget = new BudgetTotals(ownContract, BigDecimal.ZERO, BigDecimal.ZERO, ownEtc);
            visibleProjectContractValue = BigDecimal.ZERO;
        }

        BigDecimal currentBudget = budget.currentBudget();
        BigDecimal eac = latestForecast != null ? latestForecast.forecastFinalCost() : budget.actualCost().add(budget.estimateToComplete());
        BigDecimal variance = currentBudget.subtract(eac);
        return new ControlsSummary(projectId, project.getProjectCode(), project.getName(), project.getCurrency(), visibleProjectContractValue,
                contracts.original(), contracts.changes(), currentBudget, budget.committedCost(), budget.actualCost(), budget.estimateToComplete(),
                eac, variance, latestForecast, broad ? "PROJECT" : "ORGANIZATION");
    }

    @Transactional(readOnly = true)
    public List<ContractView> contracts(UUID tenantId, UUID userId, UUID projectId) {
        projectService.get(tenantId, userId, projectId);
        TenantUserEntity actor = accessService.requireActiveUser(tenantId, userId);
        UUID orgId = canSeeWholeCommercialProject(tenantId, projectId, actor) ? null : actor.getOrganizationId();
        return repository.findContracts(tenantId, projectId, orgId);
    }

    @Transactional
    public UUID createContract(UUID tenantId, UUID userId, UUID projectId, CreateContractRequest req) {
        requireCommercialEditor(tenantId, userId, projectId);
        String model = req.commercialModel() == null ? "FIXED_FEE" : req.commercialModel().toUpperCase();
        if (!COMMERCIAL_MODELS.contains(model))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported commercial model: " + model);
        if (!repository.activeParticipant(req.participantId(), tenantId, projectId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Participant is not active on this project");

        String currency = req.currency();
        if (currency == null || currency.isBlank()) currency = projectService.get(tenantId, projectId).getCurrency();
        UUID id = UUID.randomUUID();
        repository.insertContract(id, tenantId, projectId, req.participantId(), req.contractRef(), model,
                money(req.originalValue()), money(req.approvedVariations()), currency, req.startDate(), req.endDate(),
                req.status() == null ? "ACTIVE" : req.status().toUpperCase());
        return id;
    }

    @Transactional(readOnly = true)
    public BudgetView currentBudget(UUID tenantId, UUID userId, UUID projectId) {
        projectService.get(tenantId, userId, projectId);
        TenantUserEntity actor = accessService.requireActiveUser(tenantId, userId);
        if (!canSeeWholeCommercialProject(tenantId, projectId, actor))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project budget is restricted to client/consultant commercial roles");

        UUID versionId = repository.latestBudgetVersionId(tenantId, projectId);
        if (versionId == null) return null;
        return new BudgetView(repository.budgetHeader(versionId), repository.budgetLines(tenantId, projectId, versionId),
                repository.latestBudgetTotals(tenantId, projectId));
    }

    @Transactional
    public UUID createBudgetVersion(UUID tenantId, UUID userId, UUID projectId, CreateBudgetVersionRequest req) {
        TenantUserEntity actor = requireCommercialEditor(tenantId, userId, projectId);
        UUID id = UUID.randomUUID();
        repository.insertBudgetVersion(id, tenantId, projectId, repository.nextBudgetVersion(tenantId, projectId), req.label(),
                req.status()==null?"DRAFT":req.status().toUpperCase(), req.effectiveDate(), actor.getId());
        return id;
    }

    @Transactional
    public UUID addBudgetLine(UUID tenantId, UUID userId, UUID projectId, UUID versionId, CreateBudgetLineRequest req) {
        requireCommercialEditor(tenantId, userId, projectId);
        if (!repository.budgetVersionExists(versionId, tenantId, projectId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Budget version not found");
        UUID id=UUID.randomUUID();
        repository.insertBudgetLine(id,tenantId,projectId,versionId,req.parentLineId(),req.costCode(),req.name(),
                money(req.originalBudget()),money(req.approvedChanges()),money(req.committedCost()),money(req.actualCost()),
                money(req.estimateToComplete()),req.sortOrder()==null?0:req.sortOrder());
        return id;
    }

    @Transactional(readOnly = true)
    public List<ForecastView> forecasts(UUID tenantId, UUID userId, UUID projectId) {
        projectService.get(tenantId,userId,projectId);
        TenantUserEntity actor=accessService.requireActiveUser(tenantId,userId);
        UUID orgId = canSeeWholeCommercialProject(tenantId,projectId,actor) ? null : actor.getOrganizationId();
        return repository.forecasts(tenantId, projectId, orgId);
    }

    @Transactional
    public UUID createForecast(UUID tenantId, UUID userId, UUID projectId, CreateForecastRequest req) {
        TenantUserEntity actor=requireForecastEditor(tenantId,userId,projectId);
        UUID source=accessService.isTenantAdministrator(actor)?req.sourceOrganizationId():actor.getOrganizationId();
        if (source != null && !repository.activeOrganization(tenantId, projectId, source))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Forecast source organization is not active on this project");

        UUID id=UUID.randomUUID();
        repository.insertForecast(id,tenantId,projectId,source,req.snapshotDate()==null?LocalDate.now():req.snapshotDate(),
                money(req.forecastFinalCost()),money(req.estimateToComplete()),req.physicalProgressPercent(),
                req.scheduleProgressPercent(),req.notes(),actor.getId());
        return id;
    }

    private ForecastView latestForecast(UUID tenantId, UUID projectId, UUID orgId) {
        List<ForecastView> values = repository.forecasts(tenantId, projectId, orgId);
        return values.isEmpty() ? null : values.get(0);
    }

    private TenantUserEntity requireCommercialEditor(UUID tenantId,UUID userId,UUID projectId){
        projectService.get(tenantId,userId,projectId);
        TenantUserEntity actor=accessService.requireActiveUser(tenantId,userId);
        if(accessService.isTenantAdministrator(actor)) return actor;
        if(actor.getRole()!=UserRole.MANAGER && actor.getRole()!=UserRole.ADMIN)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Commercial configuration requires a manager or administrator role");
        accessService.requirePartyRole(tenantId,projectId,actor,PartyRole.CLIENT,PartyRole.CONSULTANT);
        return actor;
    }

    private TenantUserEntity requireForecastEditor(UUID tenantId, UUID userId, UUID projectId) {
        projectService.get(tenantId,userId,projectId);
        TenantUserEntity actor=accessService.requireActiveUser(tenantId,userId);
        if(accessService.isTenantAdministrator(actor)) return actor;
        if(actor.getRole()!=UserRole.MANAGER && actor.getRole()!=UserRole.ADMIN)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Forecast submission requires a manager or administrator role");
        if(actor.getOrganizationId()==null || !repository.activeOrganization(tenantId,projectId,actor.getOrganizationId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Forecast submission requires an active project organization");
        return actor;
    }

    private boolean canSeeWholeCommercialProject(UUID tenantId,UUID projectId,TenantUserEntity actor){
        if(accessService.isTenantAdministrator(actor)) return true;
        List<PartyRole> roles=accessService.rolesOnProject(tenantId,projectId,actor);
        return roles.contains(PartyRole.CLIENT)||roles.contains(PartyRole.CONSULTANT);
    }

    private static BigDecimal money(BigDecimal v){return v==null?BigDecimal.ZERO:v;}

    public record BudgetTotals(BigDecimal currentBudget,BigDecimal committedCost,BigDecimal actualCost,BigDecimal estimateToComplete){}
    public record ControlsSummary(UUID projectId,String projectCode,String projectName,String currency,BigDecimal projectContractValue,
                                  BigDecimal partyOriginalContracts,BigDecimal approvedContractChanges,BigDecimal currentBudget,BigDecimal committedCost,
                                  BigDecimal actualCost,BigDecimal estimateToComplete,BigDecimal forecastFinalCost,BigDecimal forecastVariance,
                                  ForecastView latestForecast,String visibilityScope){}
    public record ContractView(UUID id,UUID participantId,UUID organizationId,String organizationName,String partyRole,String contractRef,
                               String commercialModel,BigDecimal originalValue,BigDecimal approvedVariations,BigDecimal currentValue,String currency,
                               LocalDate startDate,LocalDate endDate,String status){}
    public record BudgetHeader(UUID id,int versionNo,String label,String status,LocalDate effectiveDate){}
    public record BudgetLineView(UUID id,UUID parentLineId,String costCode,String name,BigDecimal originalBudget,BigDecimal approvedChanges,
                                 BigDecimal currentBudget,BigDecimal committedCost,BigDecimal actualCost,BigDecimal estimateToComplete,
                                 BigDecimal forecastFinalCost,int sortOrder){}
    public record BudgetView(BudgetHeader header,List<BudgetLineView> lines,BudgetTotals totals){}
    public record ForecastView(UUID id,UUID sourceOrganizationId,String sourceOrganizationName,LocalDate snapshotDate,
                               BigDecimal forecastFinalCost,BigDecimal estimateToComplete,BigDecimal physicalProgressPercent,
                               BigDecimal scheduleProgressPercent,String notes){}
    public record CreateContractRequest(UUID participantId,String contractRef,String commercialModel,BigDecimal originalValue,
                                        BigDecimal approvedVariations,String currency,LocalDate startDate,LocalDate endDate,String status){}
    public record CreateBudgetVersionRequest(String label,String status,LocalDate effectiveDate){}
    public record CreateBudgetLineRequest(UUID parentLineId,String costCode,String name,BigDecimal originalBudget,BigDecimal approvedChanges,
                                          BigDecimal committedCost,BigDecimal actualCost,BigDecimal estimateToComplete,Integer sortOrder){}
    public record CreateForecastRequest(UUID sourceOrganizationId,LocalDate snapshotDate,BigDecimal forecastFinalCost,
                                        BigDecimal estimateToComplete,BigDecimal physicalProgressPercent,
                                        BigDecimal scheduleProgressPercent,String notes){}
}
