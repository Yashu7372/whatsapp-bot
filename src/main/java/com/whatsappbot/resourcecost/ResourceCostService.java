package com.whatsappbot.resourcecost;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.UserRole;
import com.whatsappbot.project.PartyRole;
import com.whatsappbot.project.ProjectAccessService;
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
public class ResourceCostService {
    private static final Set<String> TYPES = Set.of("PERSON","EQUIPMENT","MACHINE","VEHICLE");
    private static final Set<String> RATE_TYPES = Set.of("HOURLY","DAILY","MONTHLY","UNIT");

    private final ResourceCostRepository repository;
    private final ProjectService projectService;
    private final ProjectAccessService accessService;

    @Transactional(readOnly = true)
    public ResourceCostSummary summary(UUID tenantId, UUID userId, UUID projectId) {
        TenantUserEntity actor = commercialViewer(tenantId,userId,projectId);
        UUID orgFilter = broad(actor,tenantId,projectId) ? null : actor.getOrganizationId();
        ResourceCostRepository.CostTotals totals = repository.totals(tenantId, projectId, orgFilter);
        BigDecimal total = totals.labour().add(totals.equipment()).add(totals.manual());
        return new ResourceCostSummary(totals.labour(), totals.equipment(), totals.manual(), total,
                totals.resources(), totals.pendingTimesheets(), orgFilter==null?"PROJECT":"ORGANIZATION");
    }

    @Transactional(readOnly = true)
    public List<ResourceView> resources(UUID tenantId, UUID userId, UUID projectId) {
        TenantUserEntity actor = commercialViewer(tenantId,userId,projectId);
        UUID orgFilter = broad(actor,tenantId,projectId) ? null : actor.getOrganizationId();
        return repository.findResources(tenantId, projectId, orgFilter);
    }

    @Transactional
    public UUID createResource(UUID tenantId, UUID userId, UUID projectId, CreateResourceRequest req) {
        TenantUserEntity actor = commercialEditor(tenantId,userId,projectId);
        String type = req.resourceType()==null?"PERSON":req.resourceType().toUpperCase();
        if(!TYPES.contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported resource type");
        UUID orgId = req.organizationId()!=null?req.organizationId():actor.getOrganizationId();
        if (orgId == null || !repository.isActiveProjectOrganization(tenantId, projectId, orgId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Organization is not active on this project");
        if(!broad(actor,tenantId,projectId) && !orgId.equals(actor.getOrganizationId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot create resources for another organization");
        UUID id=UUID.randomUUID();
        repository.insertResource(id, tenantId, projectId, orgId, type, req.resourceCode(), req.displayName(), req.userId());
        return id;
    }

    @Transactional
    public UUID addRate(UUID tenantId, UUID userId, UUID projectId, UUID resourceId, CreateRateRequest req) {
        TenantUserEntity actor = commercialEditor(tenantId,userId,projectId);
        ResourceCostRepository.ResourceOwner owner = requireResourceOwner(tenantId, projectId, resourceId);
        if(!broad(actor,tenantId,projectId) && !owner.organizationId().equals(actor.getOrganizationId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot price another organization's resource");
        String type=req.rateType()==null?"HOURLY":req.rateType().toUpperCase();
        if(!RATE_TYPES.contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported rate type");
        if (req.rateAmount() == null || req.rateAmount().signum() < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Rate amount must be non-negative");
        LocalDate from = req.effectiveFrom()==null?LocalDate.now():req.effectiveFrom();
        if (req.effectiveTo()!=null && req.effectiveTo().isBefore(from))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Rate effectiveTo cannot be before effectiveFrom");
        UUID id=UUID.randomUUID();
        repository.insertRate(id, tenantId, projectId, resourceId, type, req.rateAmount(),
                req.currency()==null||req.currency().isBlank()?"AED":req.currency().toUpperCase(), from, req.effectiveTo());
        return id;
    }

    @Transactional
    public UUID submitTimesheet(UUID tenantId, UUID userId, UUID projectId, CreateTimesheetRequest req) {
        TenantUserEntity actor=accessService.requireActiveUser(tenantId,userId);
        projectService.get(tenantId,userId,projectId);
        ResourceCostRepository.ResourceOwner owner = requireResourceOwner(tenantId, projectId, req.resourceId());
        if(!"PERSON".equals(owner.resourceType()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Timesheets require a PERSON resource");
        if(!accessService.isTenantAdministrator(actor) && !owner.organizationId().equals(actor.getOrganizationId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot submit another organization's timesheet");
        if (req.hours()==null || req.hours().signum()<0 || req.hours().compareTo(BigDecimal.valueOf(24))>0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Timesheet hours must be between 0 and 24");
        UUID id=UUID.randomUUID();
        repository.insertTimesheet(id, tenantId, projectId, owner.organizationId(), req.resourceId(),
                req.workDate()==null?LocalDate.now():req.workDate(), req.hours(), req.description(), userId);
        return id;
    }

    @Transactional
    public void approveTimesheet(UUID tenantId, UUID userId, UUID projectId, UUID timesheetId, UUID budgetLineId) {
        TenantUserEntity actor=commercialEditor(tenantId,userId,projectId);
        validateBudgetLine(tenantId, projectId, budgetLineId);
        ResourceCostRepository.TimesheetRow timesheet = repository.findTimesheet(tenantId, projectId, timesheetId);
        if(timesheet==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Timesheet not found");
        if(!broad(actor,tenantId,projectId) && !timesheet.organizationId().equals(actor.getOrganizationId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot approve another organization's timesheet");
        if(!"SUBMITTED".equals(timesheet.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Timesheet is not awaiting approval");

        ResourceCostRepository.RateRow rate = requireRate(timesheet.resourceId(), timesheet.workDate());
        BigDecimal quantity = switch (rate.rateType()) {
            case "HOURLY" -> timesheet.hours();
            case "DAILY" -> BigDecimal.ONE;
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Timesheet costing supports HOURLY or DAILY rates; configured rate is " + rate.rateType());
        };
        BigDecimal amount=quantity.multiply(rate.amount());
        repository.approveTimesheet(timesheetId, userId);
        repository.insertActualCost(tenantId, projectId, timesheet.organizationId(), budgetLineId, timesheet.resourceId(),
                "TIMESHEET", timesheetId, timesheet.workDate(), quantity, rate.amount(), amount, rate.currency(), "Approved timesheet");
    }

    @Transactional
    public UUID recordEquipmentUsage(UUID tenantId, UUID userId, UUID projectId, CreateUsageRequest req) {
        TenantUserEntity actor=commercialEditor(tenantId,userId,projectId);
        validateBudgetLine(tenantId, projectId, req.budgetLineId());
        ResourceCostRepository.ResourceOwner owner = requireResourceOwner(tenantId, projectId, req.resourceId());
        if("PERSON".equals(owner.resourceType()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Equipment usage requires an equipment, machine or vehicle resource");
        if(!broad(actor,tenantId,projectId) && !owner.organizationId().equals(actor.getOrganizationId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot record another organization's equipment usage");

        LocalDate usageDate = req.usageDate()==null?LocalDate.now():req.usageDate();
        BigDecimal runningHours = money(req.runningHours());
        if (runningHours.signum()<0 || runningHours.compareTo(BigDecimal.valueOf(24))>0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Running hours must be between 0 and 24");
        ResourceCostRepository.RateRow rate = requireRate(req.resourceId(), usageDate);
        BigDecimal quantity = switch (rate.rateType()) {
            case "HOURLY" -> runningHours;
            case "DAILY" -> BigDecimal.ONE;
            case "UNIT" -> {
                if (req.quantity()==null || req.quantity().signum()<0)
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UNIT rate requires a non-negative usage quantity");
                yield req.quantity();
            }
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Per-usage costing does not support MONTHLY rates; use a period accrual workflow");
        };
        BigDecimal amount=quantity.multiply(rate.amount());
        UUID usageId=UUID.randomUUID();
        repository.insertEquipmentUsage(usageId, tenantId, projectId, owner.organizationId(), req.resourceId(), usageDate,
                runningHours, req.quantity(), req.notes(), userId);
        repository.insertActualCost(tenantId, projectId, owner.organizationId(), req.budgetLineId(), req.resourceId(),
                "EQUIPMENT_USAGE", usageId, usageDate, quantity, rate.amount(), amount, rate.currency(), "Equipment usage");
        return usageId;
    }

    @Transactional(readOnly = true)
    public List<ActualCostView> actualCosts(UUID tenantId, UUID userId, UUID projectId) {
        TenantUserEntity actor=commercialViewer(tenantId,userId,projectId);
        UUID orgFilter = broad(actor,tenantId,projectId) ? null : actor.getOrganizationId();
        return repository.findActualCosts(tenantId, projectId, orgFilter);
    }

    private void validateBudgetLine(UUID tenantId, UUID projectId, UUID budgetLineId) {
        if (!repository.validBudgetLine(tenantId, projectId, budgetLineId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Budget line does not belong to this project");
    }

    private ResourceCostRepository.ResourceOwner requireResourceOwner(UUID tenantId, UUID projectId, UUID resourceId) {
        ResourceCostRepository.ResourceOwner owner = repository.findResourceOwner(tenantId, projectId, resourceId);
        if(owner==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Resource not found");
        return owner;
    }

    private ResourceCostRepository.RateRow requireRate(UUID resourceId, LocalDate date) {
        ResourceCostRepository.RateRow rate = repository.findEffectiveRate(resourceId, date);
        if(rate==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"No effective rate is configured for this resource");
        return rate;
    }

    private TenantUserEntity commercialViewer(UUID tenantId,UUID userId,UUID projectId){
        projectService.get(tenantId,userId,projectId);
        TenantUserEntity actor=accessService.requireActiveUser(tenantId,userId);
        if(accessService.isTenantAdministrator(actor)) return actor;
        if(actor.getRole()!=UserRole.MANAGER && actor.getRole()!=UserRole.ADMIN)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Commercial cost data requires manager or administrator access");
        return actor;
    }

    private TenantUserEntity commercialEditor(UUID tenantId,UUID userId,UUID projectId){return commercialViewer(tenantId,userId,projectId);}

    private boolean broad(TenantUserEntity actor,UUID tenantId,UUID projectId){
        if(accessService.isTenantAdministrator(actor)) return true;
        List<PartyRole> roles=accessService.rolesOnProject(tenantId,projectId,actor);
        return roles.contains(PartyRole.CLIENT)||roles.contains(PartyRole.CONSULTANT);
    }

    private static BigDecimal money(BigDecimal v){return v==null?BigDecimal.ZERO:v;}

    public record ResourceCostSummary(BigDecimal labourCost,BigDecimal equipmentCost,BigDecimal manualCost,BigDecimal totalActualCost,int activeResources,int pendingTimesheets,String visibilityScope){}
    public record ResourceView(UUID id,UUID organizationId,String organizationName,String resourceType,String resourceCode,String displayName,UUID userId,boolean active){}
    public record ActualCostView(UUID id,UUID organizationId,String organizationName,UUID budgetLineId,String costCode,UUID resourceId,String resourceName,String sourceType,LocalDate costDate,BigDecimal quantity,BigDecimal amount,String currency,String description){}
    public record CreateResourceRequest(UUID organizationId,String resourceType,String resourceCode,String displayName,UUID userId){}
    public record CreateRateRequest(String rateType,BigDecimal rateAmount,String currency,LocalDate effectiveFrom,LocalDate effectiveTo){}
    public record CreateTimesheetRequest(UUID resourceId,LocalDate workDate,BigDecimal hours,String description){}
    public record CreateUsageRequest(UUID resourceId,UUID budgetLineId,LocalDate usageDate,BigDecimal runningHours,BigDecimal quantity,String notes){}
}
