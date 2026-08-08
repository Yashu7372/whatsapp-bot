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
        if (!repository.isActiveProjectOrganization(tenantId, projectId, orgId))
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
        UUID id=UUID.randomUUID();
        repository.insertRate(id, tenantId, projectId, resourceId, type, money(req.rateAmount()),
                req.currency()==null?"AED":req.currency(), req.effectiveFrom()==null?LocalDate.now():req.effectiveFrom(), req.effectiveTo());
        return id;
    }

    @Transactional
    public UUID submitTimesheet(UUID tenantId, UUID userId, UUID projectId, CreateTimesheetRequest req) {
        TenantUserEntity actor=accessService.requireActiveUser(tenantId,userId);
        projectService.get(tenantId,userId,projectId);
        ResourceCostRepository.ResourceOwner owner = requireResourceOwner(tenantId, projectId, req.resourceId());
        if(!accessService.isTenantAdministrator(actor) && !owner.organizationId().equals(actor.getOrganizationId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot submit another organization's timesheet");
        UUID id=UUID.randomUUID();
        repository.insertTimesheet(id, tenantId, projectId, owner.organizationId(), req.resourceId(), req.workDate(), req.hours(), req.description(), userId);
        return id;
    }

    @Transactional
    public void approveTimesheet(UUID tenantId, UUID userId, UUID projectId, UUID timesheetId, UUID budgetLineId) {
        TenantUserEntity actor=commercialEditor(tenantId,userId,projectId);
        ResourceCostRepository.TimesheetRow timesheet = repository.findTimesheet(tenantId, projectId, timesheetId);
        if(timesheet==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Timesheet not found");
        if(!broad(actor,tenantId,projectId) && !timesheet.organizationId().equals(actor.getOrganizationId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot approve another organization's timesheet");
        if(!"SUBMITTED".equals(timesheet.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Timesheet is not awaiting approval");

        ResourceCostRepository.RateRow rate = requireRate(timesheet.resourceId(), timesheet.workDate());
        BigDecimal quantity="DAILY".equals(rate.rateType())?BigDecimal.ONE:timesheet.hours();
        BigDecimal amount=quantity.multiply(rate.amount());
        repository.approveTimesheet(timesheetId, userId);
        repository.insertActualCost(tenantId, projectId, timesheet.organizationId(), budgetLineId, timesheet.resourceId(),
                "TIMESHEET", timesheetId, timesheet.workDate(), quantity, rate.amount(), amount, rate.currency(), "Approved timesheet");
    }

    @Transactional
    public UUID recordEquipmentUsage(UUID tenantId, UUID userId, UUID projectId, CreateUsageRequest req) {
        TenantUserEntity actor=commercialEditor(tenantId,userId,projectId);
        ResourceCostRepository.ResourceOwner owner = requireResourceOwner(tenantId, projectId, req.resourceId());
        if("PERSON".equals(owner.resourceType()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Equipment usage requires an equipment, machine or vehicle resource");
        if(!broad(actor,tenantId,projectId) && !owner.organizationId().equals(actor.getOrganizationId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot record another organization's equipment usage");

        ResourceCostRepository.RateRow rate = requireRate(req.resourceId(), req.usageDate());
        BigDecimal quantity="DAILY".equals(rate.rateType())?BigDecimal.ONE:money(req.runningHours());
        BigDecimal amount=quantity.multiply(rate.amount());
        UUID usageId=UUID.randomUUID();
        repository.insertEquipmentUsage(usageId, tenantId, projectId, owner.organizationId(), req.resourceId(), req.usageDate(),
                money(req.runningHours()), req.quantity(), req.notes(), userId);
        repository.insertActualCost(tenantId, projectId, owner.organizationId(), req.budgetLineId(), req.resourceId(),
                "EQUIPMENT_USAGE", usageId, req.usageDate(), quantity, rate.amount(), amount, rate.currency(), "Equipment usage");
        return usageId;
    }

    @Transactional(readOnly = true)
    public List<ActualCostView> actualCosts(UUID tenantId, UUID userId, UUID projectId) {
        TenantUserEntity actor=commercialViewer(tenantId,userId,projectId);
        UUID orgFilter = broad(actor,tenantId,projectId) ? null : actor.getOrganizationId();
        return repository.findActualCosts(tenantId, projectId, orgFilter);
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
