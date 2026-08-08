package com.whatsappbot.projectcontrols;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class ProjectControlsRepository {
    private final JdbcTemplate jdbc;

    MoneyTotals contractTotals(UUID tenantId, UUID projectId, UUID orgId) {
        String sql="""
            select coalesce(sum(c.original_value),0),coalesce(sum(c.approved_variations),0)
            from project_contracts c join project_participants p on p.id=c.participant_id
            where c.tenant_id=? and c.project_id=? and c.status <> 'CANCELLED'
            """+(orgId==null?"":" and p.organization_id=?");
        Object[] args=orgId==null?new Object[]{tenantId,projectId}:new Object[]{tenantId,projectId,orgId};
        return jdbc.queryForObject(sql,(rs,n)->new MoneyTotals(rs.getBigDecimal(1),rs.getBigDecimal(2)),args);
    }

    List<ProjectControlsService.ContractView> findContracts(UUID tenantId, UUID projectId, UUID orgId) {
        String sql="""
            select c.id,c.participant_id,p.organization_id,o.name,p.party_role,c.contract_ref,c.commercial_model,
                   c.original_value,c.approved_variations,c.currency,c.start_date,c.end_date,c.status
            from project_contracts c join project_participants p on p.id=c.participant_id join organizations o on o.id=p.organization_id
            where c.tenant_id=? and c.project_id=?
            """+(orgId==null?"":" and p.organization_id=?")+" order by o.name,c.contract_ref";
        Object[] args=orgId==null?new Object[]{tenantId,projectId}:new Object[]{tenantId,projectId,orgId};
        return jdbc.query(sql,(rs,n)->new ProjectControlsService.ContractView(
                rs.getObject("id",UUID.class),rs.getObject("participant_id",UUID.class),rs.getObject("organization_id",UUID.class),
                rs.getString("name"),rs.getString("party_role"),rs.getString("contract_ref"),rs.getString("commercial_model"),
                rs.getBigDecimal("original_value"),rs.getBigDecimal("approved_variations"),
                rs.getBigDecimal("original_value").add(rs.getBigDecimal("approved_variations")),rs.getString("currency"),
                date(rs.getDate("start_date")),date(rs.getDate("end_date")),rs.getString("status")),args);
    }

    boolean activeParticipant(UUID participantId, UUID tenantId, UUID projectId) {
        Integer count=jdbc.queryForObject("select count(*) from project_participants where id=? and tenant_id=? and project_id=? and active=true",Integer.class,participantId,tenantId,projectId);
        return count!=null&&count>0;
    }

    void insertContract(UUID id, UUID tenantId, UUID projectId, UUID participantId, String ref, String model,
                        BigDecimal original, BigDecimal changes, String currency, LocalDate start, LocalDate end, String status) {
        jdbc.update("""
            insert into project_contracts(id,tenant_id,project_id,participant_id,contract_ref,commercial_model,original_value,approved_variations,currency,start_date,end_date,status)
            values(?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenantId,projectId,participantId,ref,model,original,changes,currency,start,end,status);
    }

    UUID latestBudgetVersionId(UUID tenantId, UUID projectId) {
        return jdbc.query("select id from budget_versions where tenant_id=? and project_id=? order by case when status='APPROVED' then 0 else 1 end, version_no desc limit 1",
                rs->rs.next()?rs.getObject(1,UUID.class):null,tenantId,projectId);
    }

    ProjectControlsService.BudgetHeader budgetHeader(UUID versionId) {
        return jdbc.queryForObject("select version_no,label,status,effective_date from budget_versions where id=?",
                (rs,n)->new ProjectControlsService.BudgetHeader(versionId,rs.getInt(1),rs.getString(2),rs.getString(3),date(rs.getDate(4))),versionId);
    }

    List<ProjectControlsService.BudgetLineView> budgetLines(UUID tenantId, UUID projectId, UUID versionId) {
        return jdbc.query("""
            select id,parent_line_id,cost_code,name,original_budget,approved_changes,committed_cost,actual_cost,estimate_to_complete,sort_order
            from budget_lines where tenant_id=? and project_id=? and budget_version_id=? order by sort_order,cost_code
            """,(rs,n)->new ProjectControlsService.BudgetLineView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),
                rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getBigDecimal(5).add(rs.getBigDecimal(6)),rs.getBigDecimal(7),
                rs.getBigDecimal(8),rs.getBigDecimal(9),rs.getBigDecimal(8).add(rs.getBigDecimal(9)),rs.getInt(10)),tenantId,projectId,versionId);
    }

    ProjectControlsService.BudgetTotals latestBudgetTotals(UUID tenantId, UUID projectId) {
        return jdbc.query("""
            with v as (select id from budget_versions where tenant_id=? and project_id=? order by case when status='APPROVED' then 0 else 1 end,version_no desc limit 1)
            select coalesce(sum(b.original_budget+b.approved_changes),0),coalesce(sum(b.committed_cost),0),coalesce(sum(b.actual_cost),0),coalesce(sum(b.estimate_to_complete),0)
            from budget_lines b where b.budget_version_id=(select id from v)
              and not exists(select 1 from budget_lines child where child.parent_line_id=b.id)
            """,rs->rs.next()?new ProjectControlsService.BudgetTotals(rs.getBigDecimal(1),rs.getBigDecimal(2),rs.getBigDecimal(3),rs.getBigDecimal(4))
                :new ProjectControlsService.BudgetTotals(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO),tenantId,projectId);
    }

    int nextBudgetVersion(UUID tenantId, UUID projectId) {
        Integer next=jdbc.queryForObject("select coalesce(max(version_no),0)+1 from budget_versions where tenant_id=? and project_id=?",Integer.class,tenantId,projectId);
        return next==null?1:next;
    }

    void insertBudgetVersion(UUID id, UUID tenantId, UUID projectId, int version, String label, String status, LocalDate effectiveDate, UUID createdBy) {
        jdbc.update("insert into budget_versions(id,tenant_id,project_id,version_no,label,status,effective_date,created_by) values(?,?,?,?,?,?,?,?)",
                id,tenantId,projectId,version,label,status,effectiveDate,createdBy);
    }

    boolean budgetVersionExists(UUID versionId, UUID tenantId, UUID projectId) {
        Integer count=jdbc.queryForObject("select count(*) from budget_versions where id=? and tenant_id=? and project_id=?",Integer.class,versionId,tenantId,projectId);
        return count!=null&&count>0;
    }

    void insertBudgetLine(UUID id, UUID tenantId, UUID projectId, UUID versionId, UUID parentLineId, String costCode, String name,
                          BigDecimal original, BigDecimal changes, BigDecimal committed, BigDecimal actual, BigDecimal etc, int sortOrder) {
        jdbc.update("""
            insert into budget_lines(id,tenant_id,project_id,budget_version_id,parent_line_id,cost_code,name,original_budget,approved_changes,committed_cost,actual_cost,estimate_to_complete,sort_order)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenantId,projectId,versionId,parentLineId,costCode,name,original,changes,committed,actual,etc,sortOrder);
    }

    List<ProjectControlsService.ForecastView> forecasts(UUID tenantId, UUID projectId, UUID orgId) {
        String sql="""
            select f.id,f.source_organization_id,o.name,f.snapshot_date,f.forecast_final_cost,f.estimate_to_complete,
                   f.physical_progress_percent,f.schedule_progress_percent,f.notes
            from forecast_snapshots f left join organizations o on o.id=f.source_organization_id
            where f.tenant_id=? and f.project_id=?
            """+(orgId==null?"":" and f.source_organization_id=?")+" order by f.snapshot_date desc,f.created_at desc";
        Object[] args=orgId==null?new Object[]{tenantId,projectId}:new Object[]{tenantId,projectId,orgId};
        return jdbc.query(sql,(rs,n)->new ProjectControlsService.ForecastView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
                rs.getDate(4).toLocalDate(),rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getBigDecimal(7),rs.getBigDecimal(8),rs.getString(9)),args);
    }

    boolean activeOrganization(UUID tenantId, UUID projectId, UUID orgId) {
        Integer count=jdbc.queryForObject("select count(*) from project_participants where tenant_id=? and project_id=? and organization_id=? and active=true",Integer.class,tenantId,projectId,orgId);
        return count!=null&&count>0;
    }

    void insertForecast(UUID id, UUID tenantId, UUID projectId, UUID sourceOrgId, LocalDate date, BigDecimal finalCost,
                        BigDecimal etc, BigDecimal physical, BigDecimal schedule, String notes, UUID createdBy) {
        jdbc.update("""
            insert into forecast_snapshots(id,tenant_id,project_id,source_organization_id,snapshot_date,forecast_final_cost,estimate_to_complete,physical_progress_percent,schedule_progress_percent,notes,created_by)
            values(?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenantId,projectId,sourceOrgId,date,finalCost,etc,physical,schedule,notes,createdBy);
    }

    private static LocalDate date(java.sql.Date d){return d==null?null:d.toLocalDate();}
    record MoneyTotals(BigDecimal original, BigDecimal changes) {}
}
