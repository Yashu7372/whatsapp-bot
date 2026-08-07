package com.whatsappbot.resourcecost;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.UserRole;
import com.whatsappbot.project.PartyRole;
import com.whatsappbot.project.ProjectAccessService;
import com.whatsappbot.project.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbc;
    private final ProjectService projectService;
    private final ProjectAccessService accessService;

    @Transactional(readOnly = true)
    public ResourceCostSummary summary(UUID tenantId, UUID userId, UUID projectId) {
        TenantUserEntity actor = commercialViewer(tenantId,userId,projectId);
        UUID orgFilter = broad(actor,tenantId,projectId) ? null : actor.getOrganizationId();
        String suffix = orgFilter == null ? "" : " and organization_id=?";
        Object[] args = orgFilter == null ? new Object[]{tenantId,projectId} : new Object[]{tenantId,projectId,orgFilter};
        BigDecimal labour = amount("select coalesce(sum(amount),0) from actual_cost_entries where tenant_id=? and project_id=? and source_type='TIMESHEET'"+suffix,args);
        BigDecimal equipment = amount("select coalesce(sum(amount),0) from actual_cost_entries where tenant_id=? and project_id=? and source_type='EQUIPMENT_USAGE'"+suffix,args);
        BigDecimal manual = amount("select coalesce(sum(amount),0) from actual_cost_entries where tenant_id=? and project_id=? and source_type='MANUAL'"+suffix,args);
        Integer resources = count("select count(*) from project_resources where tenant_id=? and project_id=? and active=true"+suffix,args);
        Integer pending = count("select count(*) from timesheets where tenant_id=? and project_id=? and status='SUBMITTED'"+suffix,args);
        return new ResourceCostSummary(labour,equipment,manual,labour.add(equipment).add(manual),resources,pending,orgFilter==null?"PROJECT":"ORGANIZATION");
    }

    @Transactional(readOnly = true)
    public List<ResourceView> resources(UUID tenantId, UUID userId, UUID projectId) {
        TenantUserEntity actor = commercialViewer(tenantId,userId,projectId);
        boolean broad = broad(actor,tenantId,projectId);
        String sql = """
            select r.id,r.organization_id,o.name,r.resource_type,r.resource_code,r.display_name,r.user_id,r.active
            from project_resources r join organizations o on o.id=r.organization_id
            where r.tenant_id=? and r.project_id=?
            """ + (broad ? "" : " and r.organization_id=?") + " order by o.name,r.resource_type,r.display_name";
        Object[] args = broad ? new Object[]{tenantId,projectId} : new Object[]{tenantId,projectId,actor.getOrganizationId()};
        return jdbc.query(sql,(rs,n)->new ResourceView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getObject(7,UUID.class),rs.getBoolean(8)),args);
    }

    @Transactional
    public UUID createResource(UUID tenantId, UUID userId, UUID projectId, CreateResourceRequest req) {
        TenantUserEntity actor = commercialEditor(tenantId,userId,projectId);
        String type = req.resourceType()==null?"PERSON":req.resourceType().toUpperCase();
        if(!TYPES.contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported resource type");
        UUID orgId = req.organizationId()!=null?req.organizationId():actor.getOrganizationId();
        requireProjectOrganization(tenantId,projectId,orgId);
        if(!broad(actor,tenantId,projectId) && !orgId.equals(actor.getOrganizationId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot create resources for another organization");
        UUID id=UUID.randomUUID();
        jdbc.update("insert into project_resources(id,tenant_id,project_id,organization_id,resource_type,resource_code,display_name,user_id) values(?,?,?,?,?,?,?,?)",
                id,tenantId,projectId,orgId,type,req.resourceCode(),req.displayName(),req.userId());
        return id;
    }

    @Transactional
    public UUID addRate(UUID tenantId, UUID userId, UUID projectId, UUID resourceId, CreateRateRequest req) {
        TenantUserEntity actor = commercialEditor(tenantId,userId,projectId);
        ResourceOwner owner=resourceOwner(tenantId,projectId,resourceId);
        if(!broad(actor,tenantId,projectId) && !owner.organizationId().equals(actor.getOrganizationId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot price another organization's resource");
        String type=req.rateType()==null?"HOURLY":req.rateType().toUpperCase();
        if(!RATE_TYPES.contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported rate type");
        UUID id=UUID.randomUUID();
        jdbc.update("insert into resource_rates(id,tenant_id,project_id,resource_id,rate_type,rate_amount,currency,effective_from,effective_to) values(?,?,?,?,?,?,?,?,?)",
                id,tenantId,projectId,resourceId,type,money(req.rateAmount()),req.currency()==null?"AED":req.currency(),req.effectiveFrom()==null?LocalDate.now():req.effectiveFrom(),req.effectiveTo());
        return id;
    }

    @Transactional
    public UUID submitTimesheet(UUID tenantId, UUID userId, UUID projectId, CreateTimesheetRequest req) {
        TenantUserEntity actor=accessService.requireActiveUser(tenantId,userId);
        projectService.get(tenantId,userId,projectId);
        ResourceOwner owner=resourceOwner(tenantId,projectId,req.resourceId());
        if(!accessService.isTenantAdministrator(actor) && !owner.organizationId().equals(actor.getOrganizationId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot submit another organization's timesheet");
        UUID id=UUID.randomUUID();
        jdbc.update("insert into timesheets(id,tenant_id,project_id,organization_id,resource_id,work_date,hours,status,description,created_by) values(?,?,?,?,?,?,?,?,?,?)",
                id,tenantId,projectId,owner.organizationId(),req.resourceId(),req.workDate(),req.hours(),"SUBMITTED",req.description(),userId);
        return id;
    }

    @Transactional
    public void approveTimesheet(UUID tenantId, UUID userId, UUID projectId, UUID timesheetId, UUID budgetLineId) {
        TenantUserEntity actor=commercialEditor(tenantId,userId,projectId);
        TimesheetRow t=jdbc.query("select resource_id,organization_id,work_date,hours,status from timesheets where id=? and tenant_id=? and project_id=?",rs->rs.next()?new TimesheetRow(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getDate(3).toLocalDate(),rs.getBigDecimal(4),rs.getString(5)):null,timesheetId,tenantId,projectId);
        if(t==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Timesheet not found");
        if(!broad(actor,tenantId,projectId) && !t.organizationId().equals(actor.getOrganizationId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot approve another organization's timesheet");
        if(!"SUBMITTED".equals(t.status())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Timesheet is not awaiting approval");
        RateRow rate=rateFor(t.resourceId(),t.workDate());
        BigDecimal qty="DAILY".equals(rate.rateType())?BigDecimal.ONE:t.hours();
        BigDecimal amount=qty.multiply(rate.amount());
        jdbc.update("update timesheets set status='APPROVED',approved_by=?,approved_at=now(),updated_at=now() where id=?",userId,timesheetId);
        jdbc.update("insert into actual_cost_entries(tenant_id,project_id,organization_id,budget_line_id,resource_id,source_type,source_id,cost_date,quantity,unit_rate,amount,currency,description) values(?,?,?,?,?,'TIMESHEET',?,?,?,?,?,?,?)",
                tenantId,projectId,t.organizationId(),budgetLineId,t.resourceId(),timesheetId,t.workDate(),qty,rate.amount(),amount,rate.currency(),"Approved timesheet");
    }

    @Transactional
    public UUID recordEquipmentUsage(UUID tenantId, UUID userId, UUID projectId, CreateUsageRequest req) {
        TenantUserEntity actor=commercialEditor(tenantId,userId,projectId);
        ResourceOwner owner=resourceOwner(tenantId,projectId,req.resourceId());
        if(!broad(actor,tenantId,projectId) && !owner.organizationId().equals(actor.getOrganizationId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot record another organization's equipment usage");
        RateRow rate=rateFor(req.resourceId(),req.usageDate());
        BigDecimal quantity="DAILY".equals(rate.rateType())?BigDecimal.ONE:money(req.runningHours());
        BigDecimal amount=quantity.multiply(rate.amount());
        UUID usageId=UUID.randomUUID();
        jdbc.update("insert into equipment_usage(id,tenant_id,project_id,organization_id,resource_id,usage_date,running_hours,quantity,status,notes,created_by) values(?,?,?,?,?,?,?,?,?,?,?)",
                usageId,tenantId,projectId,owner.organizationId(),req.resourceId(),req.usageDate(),money(req.runningHours()),req.quantity(),"APPROVED",req.notes(),userId);
        jdbc.update("insert into actual_cost_entries(tenant_id,project_id,organization_id,budget_line_id,resource_id,source_type,source_id,cost_date,quantity,unit_rate,amount,currency,description) values(?,?,?,?,?,'EQUIPMENT_USAGE',?,?,?,?,?,?,?)",
                tenantId,projectId,owner.organizationId(),req.budgetLineId(),req.resourceId(),usageId,req.usageDate(),quantity,rate.amount(),amount,rate.currency(),"Equipment usage");
        return usageId;
    }

    @Transactional(readOnly = true)
    public List<ActualCostView> actualCosts(UUID tenantId, UUID userId, UUID projectId) {
        TenantUserEntity actor=commercialViewer(tenantId,userId,projectId); boolean broad=broad(actor,tenantId,projectId);
        String sql="""
            select a.id,a.organization_id,o.name,a.budget_line_id,b.cost_code,a.resource_id,r.display_name,a.source_type,a.cost_date,a.quantity,a.amount,a.currency,a.description
            from actual_cost_entries a join organizations o on o.id=a.organization_id
            left join budget_lines b on b.id=a.budget_line_id left join project_resources r on r.id=a.resource_id
            where a.tenant_id=? and a.project_id=?
            """+(broad?"":" and a.organization_id=?")+" order by a.cost_date desc,a.created_at desc limit 200";
        Object[] args=broad?new Object[]{tenantId,projectId}:new Object[]{tenantId,projectId,actor.getOrganizationId()};
        return jdbc.query(sql,(rs,n)->new ActualCostView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getObject(4,UUID.class),rs.getString(5),rs.getObject(6,UUID.class),rs.getString(7),rs.getString(8),rs.getDate(9).toLocalDate(),rs.getBigDecimal(10),rs.getBigDecimal(11),rs.getString(12),rs.getString(13)),args);
    }

    private TenantUserEntity commercialViewer(UUID tenantId,UUID userId,UUID projectId){
        projectService.get(tenantId,userId,projectId); TenantUserEntity actor=accessService.requireActiveUser(tenantId,userId);
        if(accessService.isTenantAdministrator(actor)) return actor;
        if(actor.getRole()!=UserRole.MANAGER && actor.getRole()!=UserRole.ADMIN) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Commercial cost data requires manager or administrator access");
        return actor;
    }
    private TenantUserEntity commercialEditor(UUID tenantId,UUID userId,UUID projectId){return commercialViewer(tenantId,userId,projectId);}
    private boolean broad(TenantUserEntity actor,UUID tenantId,UUID projectId){if(accessService.isTenantAdministrator(actor))return true;List<PartyRole> roles=accessService.rolesOnProject(tenantId,projectId,actor);return roles.contains(PartyRole.CLIENT)||roles.contains(PartyRole.CONSULTANT);}
    private void requireProjectOrganization(UUID tenantId,UUID projectId,UUID orgId){Integer c=jdbc.queryForObject("select count(*) from project_participants where tenant_id=? and project_id=? and organization_id=? and active=true",Integer.class,tenantId,projectId,orgId);if(c==null||c==0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Organization is not active on this project");}
    private ResourceOwner resourceOwner(UUID tenantId,UUID projectId,UUID id){return jdbc.query("select organization_id,resource_type from project_resources where id=? and tenant_id=? and project_id=? and active=true",rs->rs.next()?new ResourceOwner(rs.getObject(1,UUID.class),rs.getString(2)):null,id,tenantId,projectId);}
    private RateRow rateFor(UUID resourceId,LocalDate date){RateRow r=jdbc.query("select rate_type,rate_amount,currency from resource_rates where resource_id=? and effective_from<=? and (effective_to is null or effective_to>=?) order by effective_from desc limit 1",rs->rs.next()?new RateRow(rs.getString(1),rs.getBigDecimal(2),rs.getString(3)):null,resourceId,date,date);if(r==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"No effective rate is configured for this resource");return r;}
    private BigDecimal amount(String sql,Object[] args){return jdbc.queryForObject(sql,BigDecimal.class,args);}
    private Integer count(String sql,Object[] args){return jdbc.queryForObject(sql,Integer.class,args);}
    private static BigDecimal money(BigDecimal v){return v==null?BigDecimal.ZERO:v;}

    private record ResourceOwner(UUID organizationId,String resourceType){}
    private record RateRow(String rateType,BigDecimal amount,String currency){}
    private record TimesheetRow(UUID resourceId,UUID organizationId,LocalDate workDate,BigDecimal hours,String status){}
    public record ResourceCostSummary(BigDecimal labourCost,BigDecimal equipmentCost,BigDecimal manualCost,BigDecimal totalActualCost,int activeResources,int pendingTimesheets,String visibilityScope){}
    public record ResourceView(UUID id,UUID organizationId,String organizationName,String resourceType,String resourceCode,String displayName,UUID userId,boolean active){}
    public record ActualCostView(UUID id,UUID organizationId,String organizationName,UUID budgetLineId,String costCode,UUID resourceId,String resourceName,String sourceType,LocalDate costDate,BigDecimal quantity,BigDecimal amount,String currency,String description){}
    public record CreateResourceRequest(UUID organizationId,String resourceType,String resourceCode,String displayName,UUID userId){}
    public record CreateRateRequest(String rateType,BigDecimal rateAmount,String currency,LocalDate effectiveFrom,LocalDate effectiveTo){}
    public record CreateTimesheetRequest(UUID resourceId,LocalDate workDate,BigDecimal hours,String description){}
    public record CreateUsageRequest(UUID resourceId,UUID budgetLineId,LocalDate usageDate,BigDecimal runningHours,BigDecimal quantity,String notes){}
}
