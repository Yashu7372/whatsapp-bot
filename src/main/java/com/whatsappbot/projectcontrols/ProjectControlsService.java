package com.whatsappbot.projectcontrols;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.UserRole;
import com.whatsappbot.project.PartyRole;
import com.whatsappbot.project.ProjectAccessService;
import com.whatsappbot.project.ProjectEntity;
import com.whatsappbot.project.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectControlsService {
    private final JdbcTemplate jdbc;
    private final ProjectService projectService;
    private final ProjectAccessService accessService;

    @Transactional(readOnly = true)
    public ControlsSummary summary(UUID tenantId, UUID userId, UUID projectId) {
        ProjectEntity project = projectService.get(tenantId, userId, projectId);
        TenantUserEntity actor = accessService.requireActiveUser(tenantId, userId);
        boolean broad = canSeeWholeCommercialProject(tenantId, projectId, actor);
        UUID orgId = actor.getOrganizationId();

        MoneyTotals contracts = broad
                ? jdbc.queryForObject("""
                    select coalesce(sum(original_value),0), coalesce(sum(approved_variations),0)
                    from project_contracts where tenant_id=? and project_id=? and status <> 'CANCELLED'
                    """, (rs, n) -> new MoneyTotals(rs.getBigDecimal(1), rs.getBigDecimal(2)), tenantId, projectId)
                : jdbc.queryForObject("""
                    select coalesce(sum(c.original_value),0), coalesce(sum(c.approved_variations),0)
                    from project_contracts c join project_participants p on p.id=c.participant_id
                    where c.tenant_id=? and c.project_id=? and p.organization_id=? and c.status <> 'CANCELLED'
                    """, (rs, n) -> new MoneyTotals(rs.getBigDecimal(1), rs.getBigDecimal(2)), tenantId, projectId, orgId);

        BudgetTotals budget = latestBudgetTotals(tenantId, projectId);
        ForecastView latestForecast = latestForecast(tenantId, projectId, broad ? null : orgId);
        BigDecimal currentBudget = budget.currentBudget();
        BigDecimal eac = latestForecast != null ? latestForecast.forecastFinalCost()
                : budget.actualCost().add(budget.estimateToComplete());
        BigDecimal variance = currentBudget.subtract(eac);

        return new ControlsSummary(projectId, project.getProjectCode(), project.getName(), project.getCurrency(),
                project.getContractValue() == null ? BigDecimal.ZERO : project.getContractValue(),
                contracts.original(), contracts.changes(), budget.currentBudget(), budget.committedCost(),
                budget.actualCost(), budget.estimateToComplete(), eac, variance,
                latestForecast, broad ? "PROJECT" : "ORGANIZATION");
    }

    @Transactional(readOnly = true)
    public List<ContractView> contracts(UUID tenantId, UUID userId, UUID projectId) {
        projectService.get(tenantId, userId, projectId);
        TenantUserEntity actor = accessService.requireActiveUser(tenantId, userId);
        boolean broad = canSeeWholeCommercialProject(tenantId, projectId, actor);
        String sql = """
            select c.id,c.participant_id,p.organization_id,o.name,p.party_role,c.contract_ref,c.commercial_model,
                   c.original_value,c.approved_variations,c.currency,c.start_date,c.end_date,c.status
            from project_contracts c
            join project_participants p on p.id=c.participant_id
            join organizations o on o.id=p.organization_id
            where c.tenant_id=? and c.project_id=?
            """ + (broad ? "" : " and p.organization_id=?") + " order by o.name,c.contract_ref";
        Object[] args = broad ? new Object[]{tenantId, projectId} : new Object[]{tenantId, projectId, actor.getOrganizationId()};
        return jdbc.query(sql, (rs, n) -> new ContractView(
                rs.getObject("id", UUID.class), rs.getObject("participant_id", UUID.class),
                rs.getObject("organization_id", UUID.class), rs.getString("name"), rs.getString("party_role"),
                rs.getString("contract_ref"), rs.getString("commercial_model"), rs.getBigDecimal("original_value"),
                rs.getBigDecimal("approved_variations"), rs.getBigDecimal("original_value").add(rs.getBigDecimal("approved_variations")),
                rs.getString("currency"), date(rs.getDate("start_date")), date(rs.getDate("end_date")), rs.getString("status")), args);
    }

    @Transactional
    public UUID createContract(UUID tenantId, UUID userId, UUID projectId, CreateContractRequest req) {
        requireCommercialEditor(tenantId, userId, projectId);
        Integer count = jdbc.queryForObject("""
            select count(*) from project_participants where id=? and tenant_id=? and project_id=? and active=true
            """, Integer.class, req.participantId(), tenantId, projectId);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Participant is not active on this project");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into project_contracts(id,tenant_id,project_id,participant_id,contract_ref,commercial_model,original_value,approved_variations,currency,start_date,end_date,status)
            values(?,?,?,?,?,?,?,?,?,?,?,?)
            """, id, tenantId, projectId, req.participantId(), req.contractRef(), req.commercialModel(), money(req.originalValue()),
                money(req.approvedVariations()), req.currency(), req.startDate(), req.endDate(), req.status() == null ? "ACTIVE" : req.status());
        return id;
    }

    @Transactional(readOnly = true)
    public BudgetView currentBudget(UUID tenantId, UUID userId, UUID projectId) {
        projectService.get(tenantId, userId, projectId);
        UUID versionId = jdbc.query("""
            select id from budget_versions where tenant_id=? and project_id=? order by case when status='APPROVED' then 0 else 1 end, version_no desc limit 1
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, tenantId, projectId);
        if (versionId == null) return null;
        BudgetHeader header = jdbc.queryForObject("select version_no,label,status,effective_date from budget_versions where id=?",
                (rs,n)->new BudgetHeader(versionId,rs.getInt(1),rs.getString(2),rs.getString(3),date(rs.getDate(4))),versionId);
        List<BudgetLineView> lines = jdbc.query("""
            select id,parent_line_id,cost_code,name,original_budget,approved_changes,committed_cost,actual_cost,estimate_to_complete,sort_order
            from budget_lines where tenant_id=? and project_id=? and budget_version_id=? order by sort_order,cost_code
            """, (rs,n)->new BudgetLineView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),
                    rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getBigDecimal(5).add(rs.getBigDecimal(6)),rs.getBigDecimal(7),
                    rs.getBigDecimal(8),rs.getBigDecimal(9),rs.getBigDecimal(8).add(rs.getBigDecimal(9)),rs.getInt(10)), tenantId,projectId,versionId);
        return new BudgetView(header, lines, latestBudgetTotals(tenantId, projectId));
    }

    @Transactional
    public UUID createBudgetVersion(UUID tenantId, UUID userId, UUID projectId, CreateBudgetVersionRequest req) {
        TenantUserEntity actor = requireCommercialEditor(tenantId, userId, projectId);
        Integer next = jdbc.queryForObject("select coalesce(max(version_no),0)+1 from budget_versions where tenant_id=? and project_id=?", Integer.class, tenantId, projectId);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into budget_versions(id,tenant_id,project_id,version_no,label,status,effective_date,created_by) values(?,?,?,?,?,?,?,?)",
                id,tenantId,projectId,next,req.label(),req.status()==null?"DRAFT":req.status(),req.effectiveDate(),actor.getId());
        return id;
    }

    @Transactional
    public UUID addBudgetLine(UUID tenantId, UUID userId, UUID projectId, UUID versionId, CreateBudgetLineRequest req) {
        requireCommercialEditor(tenantId, userId, projectId);
        Integer count=jdbc.queryForObject("select count(*) from budget_versions where id=? and tenant_id=? and project_id=?",Integer.class,versionId,tenantId,projectId);
        if(count==null||count==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Budget version not found");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into budget_lines(id,tenant_id,project_id,budget_version_id,parent_line_id,cost_code,name,original_budget,approved_changes,committed_cost,actual_cost,estimate_to_complete,sort_order)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenantId,projectId,versionId,req.parentLineId(),req.costCode(),req.name(),money(req.originalBudget()),money(req.approvedChanges()),
                money(req.committedCost()),money(req.actualCost()),money(req.estimateToComplete()),req.sortOrder()==null?0:req.sortOrder());
        return id;
    }

    @Transactional(readOnly = true)
    public List<ForecastView> forecasts(UUID tenantId, UUID userId, UUID projectId) {
        projectService.get(tenantId,userId,projectId);
        TenantUserEntity actor=accessService.requireActiveUser(tenantId,userId);
        boolean broad=canSeeWholeCommercialProject(tenantId,projectId,actor);
        return forecastQuery(tenantId,projectId,broad?null:actor.getOrganizationId());
    }

    @Transactional
    public UUID createForecast(UUID tenantId, UUID userId, UUID projectId, CreateForecastRequest req) {
        TenantUserEntity actor=requireCommercialEditor(tenantId,userId,projectId);
        UUID source=req.sourceOrganizationId()!=null?req.sourceOrganizationId():actor.getOrganizationId();
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into forecast_snapshots(id,tenant_id,project_id,source_organization_id,snapshot_date,forecast_final_cost,estimate_to_complete,physical_progress_percent,schedule_progress_percent,notes,created_by)
            values(?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenantId,projectId,source,req.snapshotDate()==null?LocalDate.now():req.snapshotDate(),money(req.forecastFinalCost()),
                money(req.estimateToComplete()),req.physicalProgressPercent(),req.scheduleProgressPercent(),req.notes(),actor.getId());
        return id;
    }

    private BudgetTotals latestBudgetTotals(UUID tenantId, UUID projectId){
        return jdbc.query("""
            with v as (select id from budget_versions where tenant_id=? and project_id=? order by case when status='APPROVED' then 0 else 1 end,version_no desc limit 1)
            select coalesce(sum(original_budget+approved_changes),0),coalesce(sum(committed_cost),0),coalesce(sum(actual_cost),0),coalesce(sum(estimate_to_complete),0)
            from budget_lines where budget_version_id=(select id from v)
            """,rs->rs.next()?new BudgetTotals(rs.getBigDecimal(1),rs.getBigDecimal(2),rs.getBigDecimal(3),rs.getBigDecimal(4)):new BudgetTotals(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO),tenantId,projectId);
    }

    private List<ForecastView> forecastQuery(UUID tenantId,UUID projectId,UUID orgId){
        String sql="""
            select f.id,f.source_organization_id,o.name,f.snapshot_date,f.forecast_final_cost,f.estimate_to_complete,
                   f.physical_progress_percent,f.schedule_progress_percent,f.notes
            from forecast_snapshots f left join organizations o on o.id=f.source_organization_id
            where f.tenant_id=? and f.project_id=?
            """+(orgId==null?"":" and f.source_organization_id=?")+" order by f.snapshot_date desc,f.created_at desc";
        Object[] args=orgId==null?new Object[]{tenantId,projectId}:new Object[]{tenantId,projectId,orgId};
        return jdbc.query(sql,(rs,n)->new ForecastView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
                rs.getDate(4).toLocalDate(),rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getBigDecimal(7),rs.getBigDecimal(8),rs.getString(9)),args);
    }

    private ForecastView latestForecast(UUID tenantId,UUID projectId,UUID orgId){
        List<ForecastView> values=forecastQuery(tenantId,projectId,orgId); return values.isEmpty()?null:values.get(0);
    }

    private TenantUserEntity requireCommercialEditor(UUID tenantId,UUID userId,UUID projectId){
        projectService.get(tenantId,userId,projectId);
        TenantUserEntity actor=accessService.requireActiveUser(tenantId,userId);
        if(accessService.isTenantAdministrator(actor)) return actor;
        if(actor.getRole()!= UserRole.MANAGER && actor.getRole()!=UserRole.ADMIN)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Commercial configuration requires a manager or administrator role");
        accessService.requirePartyRole(tenantId,projectId,actor,PartyRole.CLIENT,PartyRole.CONSULTANT);
        return actor;
    }

    private boolean canSeeWholeCommercialProject(UUID tenantId,UUID projectId,TenantUserEntity actor){
        if(accessService.isTenantAdministrator(actor)) return true;
        List<PartyRole> roles=accessService.rolesOnProject(tenantId,projectId,actor);
        return roles.contains(PartyRole.CLIENT)||roles.contains(PartyRole.CONSULTANT);
    }

    private static BigDecimal money(BigDecimal v){return v==null?BigDecimal.ZERO:v;}
    private static LocalDate date(Date d){return d==null?null:d.toLocalDate();}

    private record MoneyTotals(BigDecimal original,BigDecimal changes){}
    public record BudgetTotals(BigDecimal currentBudget,BigDecimal committedCost,BigDecimal actualCost,BigDecimal estimateToComplete){}
    public record ControlsSummary(UUID projectId,String projectCode,String projectName,String currency,BigDecimal projectContractValue,
                                  BigDecimal partyOriginalContracts,BigDecimal approvedContractChanges,BigDecimal currentBudget,BigDecimal committedCost,
                                  BigDecimal actualCost,BigDecimal estimateToComplete,BigDecimal forecastFinalCost,BigDecimal forecastVariance,
                                  ForecastView latestForecast,String visibilityScope){}
    public record ContractView(UUID id,UUID participantId,UUID organizationId,String organizationName,String partyRole,String contractRef,
                               String commercialModel,BigDecimal originalValue,BigDecimal approvedVariations,BigDecimal currentValue,String currency,
                               LocalDate startDate,LocalDate endDate,String status){}
    public record BudgetHeader(UUID id,int versionNo,String label,String status,LocalDate effectiveDate,UUID budgetVersionId){}
    public record BudgetLineView(UUID id,UUID parentLineId,String costCode,String name,BigDecimal originalBudget,BigDecimal approvedChanges,
                                 BigDecimal currentBudget,BigDecimal committedCost,BigDecimal actualCost,BigDecimal estimateToComplete,
                                 BigDecimal forecastFinalCost,int sortOrder){}
    public record BudgetView(BudgetHeader header,List<BudgetLineView> lines,BudgetTotals totals){}
    public record ForecastView(UUID id,UUID sourceOrganizationId,String sourceOrganizationName,LocalDate snapshotDate,BigDecimal forecastFinalCost,
                               BigDecimal estimateToComplete,BigDecimal physicalProgressPercent,BigDecimal scheduleProgressPercent,String notes){}

    public record CreateContractRequest(UUID participantId,String contractRef,String commercialModel,BigDecimal originalValue,BigDecimal approvedVariations,
                                        String currency,LocalDate startDate,LocalDate endDate,String status){}
    public record CreateBudgetVersionRequest(String label,String status,LocalDate effectiveDate){}
    public record CreateBudgetLineRequest(UUID parentLineId,String costCode,String name,BigDecimal originalBudget,BigDecimal approvedChanges,
                                          BigDecimal committedCost,BigDecimal actualCost,BigDecimal estimateToComplete,Integer sortOrder){}
    public record CreateForecastRequest(UUID sourceOrganizationId,LocalDate snapshotDate,BigDecimal forecastFinalCost,BigDecimal estimateToComplete,
                                        BigDecimal physicalProgressPercent,BigDecimal scheduleProgressPercent,String notes){}
}
