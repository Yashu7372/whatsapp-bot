package com.whatsappbot.forecast;

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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ForecastIntelligenceService {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private final JdbcTemplate jdbc;
    private final ProjectService projectService;
    private final ProjectAccessService accessService;

    @Transactional
    public Dashboard refresh(UUID tenantId, UUID userId, UUID projectId) {
        TenantUserEntity actor = requireViewer(tenantId, userId, projectId);
        requireWholeProject(actor, tenantId, projectId);
        Totals t = totals(tenantId, projectId);
        BigDecimal pending = value("select coalesce(sum(requested_amount),0) from project_variations where tenant_id=? and project_id=? and status in ('PROPOSED','UNDER_REVIEW')", tenantId, projectId);
        Progress p = progress(tenantId, projectId);
        BigDecimal baseEac = t.actual().add(t.etc()).max(t.committed());
        BigDecimal exposureEac = baseEac.add(pending);
        BigDecimal variance = t.budget().subtract(exposureEac);
        BigDecimal costPct = percent(t.actual(), t.budget());
        UUID snapshotId = upsertSnapshot(tenantId, projectId, userId, t, pending, baseEac, exposureEac, variance, p, costPct);
        createWarnings(tenantId, projectId, snapshotId, t.budget(), exposureEac, pending, costPct, p);
        createConsultantKpis(tenantId, projectId, exposureEac, t.budget());
        return dashboard(tenantId, userId, projectId);
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(UUID tenantId, UUID userId, UUID projectId) {
        TenantUserEntity actor = requireViewer(tenantId, userId, projectId);
        requireWholeProject(actor, tenantId, projectId);
        List<ForecastSnapshot> history = history(tenantId, projectId, 12);
        ForecastSnapshot latest = history.isEmpty() ? null : history.get(0);
        List<WarningView> warnings = latest == null ? List.of() : warnings(latest.id());
        return new Dashboard(latest, warnings, kpis(tenantId, projectId), history);
    }

    private UUID upsertSnapshot(UUID tenantId, UUID projectId, UUID userId, Totals t, BigDecimal pending,
                                BigDecimal baseEac, BigDecimal exposureEac, BigDecimal variance, Progress p, BigDecimal costPct) {
        LocalDate today = LocalDate.now();
        UUID id = jdbc.query("select id from control_forecast_snapshots where project_id=? and snapshot_date=?", rs -> rs.next() ? rs.getObject(1, UUID.class) : UUID.randomUUID(), projectId, today);
        jdbc.update("""
            insert into control_forecast_snapshots(id,tenant_id,project_id,snapshot_date,current_budget,actual_cost,committed_cost,estimate_to_complete,
              pending_variation_exposure,base_eac,exposure_eac,forecast_variance,physical_progress_percent,schedule_progress_percent,cost_consumption_percent,created_by)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict(project_id,snapshot_date) do update set
              current_budget=excluded.current_budget,actual_cost=excluded.actual_cost,committed_cost=excluded.committed_cost,
              estimate_to_complete=excluded.estimate_to_complete,pending_variation_exposure=excluded.pending_variation_exposure,
              base_eac=excluded.base_eac,exposure_eac=excluded.exposure_eac,forecast_variance=excluded.forecast_variance,
              physical_progress_percent=excluded.physical_progress_percent,schedule_progress_percent=excluded.schedule_progress_percent,
              cost_consumption_percent=excluded.cost_consumption_percent
            """, id,tenantId,projectId,today,t.budget(),t.actual(),t.committed(),t.etc(),pending,baseEac,exposureEac,variance,p.physical(),p.schedule(),costPct,userId);
        return jdbc.queryForObject("select id from control_forecast_snapshots where project_id=? and snapshot_date=?", UUID.class, projectId, today);
    }

    private void createWarnings(UUID tenantId, UUID projectId, UUID snapshotId, BigDecimal budget, BigDecimal eac,
                                BigDecimal pending, BigDecimal costPct, Progress p) {
        jdbc.update("delete from early_warning_signals where forecast_snapshot_id=?", snapshotId);
        List<WarningSeed> seeds = new ArrayList<>();
        BigDecimal overrun = eac.compareTo(budget)>0 ? percent(eac.subtract(budget),budget) : BigDecimal.ZERO;
        if(overrun.signum()>0) seeds.add(seed("FORECAST_OVERRUN",overrun,BigDecimal.valueOf(5),"Forecast final cost exceeds current budget"));
        BigDecimal varPct=percent(pending,budget);
        if(varPct.compareTo(BigDecimal.valueOf(5))>=0) seeds.add(seed("VARIATION_EXPOSURE",varPct,BigDecimal.valueOf(5),"Open variation exposure is material"));
        if(p.physical()!=null){BigDecimal gap=costPct.subtract(p.physical());if(gap.compareTo(BigDecimal.TEN)>=0) seeds.add(seed("COST_AHEAD_OF_PROGRESS",gap,BigDecimal.TEN,"Cost consumption is ahead of physical progress"));}
        if(p.physical()!=null&&p.schedule()!=null){BigDecimal gap=p.schedule().subtract(p.physical());if(gap.compareTo(BigDecimal.TEN)>=0) seeds.add(seed("PROGRESS_BEHIND_PLAN",gap,BigDecimal.TEN,"Physical progress is behind programme progress"));}
        for(WarningSeed s:seeds) jdbc.update("insert into early_warning_signals(tenant_id,project_id,forecast_snapshot_id,signal_code,severity,title,description,metric_value,threshold_value) values(?,?,?,?,?,?,?,?,?)",
                tenantId,projectId,snapshotId,s.code(),s.severity(),s.title(),s.title(),s.metric(),s.threshold());
    }

    private void createConsultantKpis(UUID tenantId,UUID projectId,BigDecimal controlForecast,BigDecimal budget){
        List<UUID> orgs=jdbc.query("select distinct organization_id from project_participants where tenant_id=? and project_id=? and active=true and party_role='CONSULTANT'",(rs,n)->rs.getObject(1,UUID.class),tenantId,projectId);
        for(UUID orgId:orgs){
            int due=count("select count(*) from documents where tenant_id=? and project_id=? and originator_org_id=? and due_at is not null",tenantId,projectId,orgId);
            int overdue=count("select count(*) from documents where tenant_id=? and project_id=? and originator_org_id=? and due_at<now() and status<>'APPROVED'",tenantId,projectId,orgId);
            BigDecimal sla=due==0?HUNDRED:HUNDRED.subtract(BigDecimal.valueOf(overdue).multiply(HUNDRED).divide(BigDecimal.valueOf(due),2,RoundingMode.HALF_UP)).max(BigDecimal.ZERO);
            BigDecimal party=jdbc.query("select forecast_final_cost from forecast_snapshots where tenant_id=? and project_id=? and source_organization_id=? order by snapshot_date desc,created_at desc limit 1",rs->rs.next()?rs.getBigDecimal(1):null,tenantId,projectId,orgId);
            BigDecimal gap=party==null?null:party.subtract(controlForecast).abs();
            BigDecimal alignment=party==null?HUNDRED:HUNDRED.subtract(percent(gap,budget)).max(BigDecimal.ZERO);
            BigDecimal overall=sla.multiply(BigDecimal.valueOf(.6)).add(alignment.multiply(BigDecimal.valueOf(.4))).setScale(2,RoundingMode.HALF_UP);
            jdbc.update("""
                insert into consultant_kpi_snapshots(tenant_id,project_id,organization_id,snapshot_date,document_sla_health,forecast_alignment,overall_control_health,overdue_documents,due_documents,latest_party_forecast,control_forecast,forecast_gap)
                values(?,?,?,?,?,?,?,?,?,?,?,?) on conflict(project_id,organization_id,snapshot_date) do update set
                document_sla_health=excluded.document_sla_health,forecast_alignment=excluded.forecast_alignment,overall_control_health=excluded.overall_control_health,
                overdue_documents=excluded.overdue_documents,due_documents=excluded.due_documents,latest_party_forecast=excluded.latest_party_forecast,control_forecast=excluded.control_forecast,forecast_gap=excluded.forecast_gap
                """,tenantId,projectId,orgId,LocalDate.now(),sla,alignment,overall,overdue,due,party,controlForecast,gap);
        }
    }

    private Totals totals(UUID tenantId,UUID projectId){return jdbc.query("""
        with v as(select id from budget_versions where tenant_id=? and project_id=? order by case when status='APPROVED' then 0 else 1 end,version_no desc limit 1)
        select coalesce(sum(original_budget+approved_changes),0),coalesce(sum(actual_cost),0),coalesce(sum(committed_cost),0),coalesce(sum(estimate_to_complete),0)
        from budget_lines b where budget_version_id=(select id from v) and not exists(select 1 from budget_lines c where c.parent_line_id=b.id)
        """,rs->rs.next()?new Totals(rs.getBigDecimal(1),rs.getBigDecimal(2),rs.getBigDecimal(3),rs.getBigDecimal(4)):new Totals(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO),tenantId,projectId);}
    private Progress progress(UUID tenantId,UUID projectId){return jdbc.query("select physical_progress_percent,schedule_progress_percent from forecast_snapshots where tenant_id=? and project_id=? and (physical_progress_percent is not null or schedule_progress_percent is not null) order by snapshot_date desc,created_at desc limit 1",rs->rs.next()?new Progress(rs.getBigDecimal(1),rs.getBigDecimal(2)):new Progress(null,null),tenantId,projectId);}
    private List<ForecastSnapshot> history(UUID tenantId,UUID projectId,int limit){return jdbc.query("select id,snapshot_date,current_budget,actual_cost,committed_cost,estimate_to_complete,pending_variation_exposure,base_eac,exposure_eac,forecast_variance,physical_progress_percent,schedule_progress_percent,cost_consumption_percent from control_forecast_snapshots where tenant_id=? and project_id=? order by snapshot_date desc limit ?",(rs,n)->new ForecastSnapshot(rs.getObject(1,UUID.class),rs.getDate(2).toLocalDate(),rs.getBigDecimal(3),rs.getBigDecimal(4),rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getBigDecimal(7),rs.getBigDecimal(8),rs.getBigDecimal(9),rs.getBigDecimal(10),rs.getBigDecimal(11),rs.getBigDecimal(12),rs.getBigDecimal(13)),tenantId,projectId,limit);}
    private List<WarningView> warnings(UUID snapshotId){return jdbc.query("select signal_code,severity,title,metric_value,threshold_value from early_warning_signals where forecast_snapshot_id=? order by case severity when 'CRITICAL' then 0 else 1 end",(rs,n)->new WarningView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getBigDecimal(4),rs.getBigDecimal(5)),snapshotId);}
    private List<KpiView> kpis(UUID tenantId,UUID projectId){return jdbc.query("select k.organization_id,o.name,k.snapshot_date,k.document_sla_health,k.forecast_alignment,k.overall_control_health,k.overdue_documents,k.due_documents,k.latest_party_forecast,k.control_forecast,k.forecast_gap from consultant_kpi_snapshots k join organizations o on o.id=k.organization_id where k.tenant_id=? and k.project_id=? and k.snapshot_date=(select max(snapshot_date) from consultant_kpi_snapshots where tenant_id=? and project_id=?) order by k.overall_control_health asc",(rs,n)->new KpiView(rs.getObject(1,UUID.class),rs.getString(2),rs.getDate(3).toLocalDate(),rs.getBigDecimal(4),rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getInt(7),rs.getInt(8),rs.getBigDecimal(9),rs.getBigDecimal(10),rs.getBigDecimal(11)),tenantId,projectId,tenantId,projectId);}
    private TenantUserEntity requireViewer(UUID tenantId,UUID userId,UUID projectId){projectService.get(tenantId,userId,projectId);TenantUserEntity a=accessService.requireActiveUser(tenantId,userId);if(accessService.isTenantAdministrator(a))return a;if(a.getRole()!=UserRole.MANAGER&&a.getRole()!=UserRole.ADMIN)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Project forecasting requires commercial manager or administrator access");return a;}
    private void requireWholeProject(TenantUserEntity a,UUID tenantId,UUID projectId){if(accessService.isTenantAdministrator(a))return;List<PartyRole> roles=accessService.rolesOnProject(tenantId,projectId,a);if(!roles.contains(PartyRole.CLIENT)&&!roles.contains(PartyRole.CONSULTANT))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Project-wide forecasting is restricted to client/consultant commercial roles");}
    private BigDecimal value(String sql,Object...args){BigDecimal v=jdbc.queryForObject(sql,BigDecimal.class,args);return v==null?BigDecimal.ZERO:v;}
    private int count(String sql,Object...args){Integer v=jdbc.queryForObject(sql,Integer.class,args);return v==null?0:v;}
    private static BigDecimal percent(BigDecimal v,BigDecimal b){return b==null||b.signum()==0?BigDecimal.ZERO:v.multiply(HUNDRED).divide(b,2,RoundingMode.HALF_UP);}
    private static WarningSeed seed(String c,BigDecimal m,BigDecimal t,String title){return new WarningSeed(c,m.compareTo(t.multiply(BigDecimal.valueOf(2)))>=0?"CRITICAL":"ATTENTION",title,m,t);}

    private record Totals(BigDecimal budget,BigDecimal actual,BigDecimal committed,BigDecimal etc){}
    private record Progress(BigDecimal physical,BigDecimal schedule){}
    private record WarningSeed(String code,String severity,String title,BigDecimal metric,BigDecimal threshold){}
    public record Dashboard(ForecastSnapshot latest,List<WarningView>warnings,List<KpiView>consultantKpis,List<ForecastSnapshot>history){}
    public record ForecastSnapshot(UUID id,LocalDate snapshotDate,BigDecimal currentBudget,BigDecimal actualCost,BigDecimal committedCost,BigDecimal estimateToComplete,BigDecimal pendingVariationExposure,BigDecimal baseEac,BigDecimal exposureEac,BigDecimal forecastVariance,BigDecimal physicalProgressPercent,BigDecimal scheduleProgressPercent,BigDecimal costConsumptionPercent){}
    public record WarningView(String code,String severity,String title,BigDecimal metricValue,BigDecimal thresholdValue){}
    public record KpiView(UUID organizationId,String organizationName,LocalDate snapshotDate,BigDecimal documentSlaHealth,BigDecimal forecastAlignment,BigDecimal overallControlHealth,int overdueDocuments,int dueDocuments,BigDecimal latestPartyForecast,BigDecimal controlForecast,BigDecimal forecastGap){}
}
