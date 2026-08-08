package com.whatsappbot.forecast;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class ForecastIntelligenceRepository {
    private final JdbcTemplate jdbc;

    Totals loadTotals(UUID tenantId, UUID projectId) {
        return jdbc.query("""
            with v as(select id from budget_versions where tenant_id=? and project_id=? order by case when status='APPROVED' then 0 else 1 end,version_no desc limit 1)
            select coalesce(sum(original_budget+approved_changes),0),coalesce(sum(actual_cost),0),coalesce(sum(committed_cost),0),coalesce(sum(estimate_to_complete),0)
            from budget_lines b where budget_version_id=(select id from v) and not exists(select 1 from budget_lines c where c.parent_line_id=b.id)
            """, rs -> rs.next() ? new Totals(rs.getBigDecimal(1),rs.getBigDecimal(2),rs.getBigDecimal(3),rs.getBigDecimal(4))
                    : new Totals(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO), tenantId, projectId);
    }

    BigDecimal pendingVariationExposure(UUID tenantId, UUID projectId) {
        BigDecimal value = jdbc.queryForObject("select coalesce(sum(requested_amount),0) from project_variations where tenant_id=? and project_id=? and status in ('PROPOSED','UNDER_REVIEW')",
                BigDecimal.class, tenantId, projectId);
        return value == null ? BigDecimal.ZERO : value;
    }

    Progress latestProgress(UUID tenantId, UUID projectId) {
        return jdbc.query("select physical_progress_percent,schedule_progress_percent from forecast_snapshots where tenant_id=? and project_id=? and (physical_progress_percent is not null or schedule_progress_percent is not null) order by snapshot_date desc,created_at desc limit 1",
                rs -> rs.next() ? new Progress(rs.getBigDecimal(1),rs.getBigDecimal(2)) : new Progress(null,null), tenantId, projectId);
    }

    UUID upsertSnapshot(UUID tenantId, UUID projectId, UUID userId, LocalDate date, Totals t, BigDecimal pending,
                        BigDecimal baseEac, BigDecimal exposureEac, BigDecimal variance, Progress p, BigDecimal costPct) {
        UUID id = jdbc.query("select id from control_forecast_snapshots where project_id=? and snapshot_date=?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : UUID.randomUUID(), projectId, date);
        jdbc.update("""
            insert into control_forecast_snapshots(id,tenant_id,project_id,snapshot_date,current_budget,actual_cost,committed_cost,estimate_to_complete,
              pending_variation_exposure,base_eac,exposure_eac,forecast_variance,physical_progress_percent,schedule_progress_percent,cost_consumption_percent,created_by)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict(project_id,snapshot_date) do update set
              current_budget=excluded.current_budget,actual_cost=excluded.actual_cost,committed_cost=excluded.committed_cost,
              estimate_to_complete=excluded.estimate_to_complete,pending_variation_exposure=excluded.pending_variation_exposure,
              base_eac=excluded.base_eac,exposure_eac=excluded.exposure_eac,forecast_variance=excluded.forecast_variance,
              physical_progress_percent=excluded.physical_progress_percent,schedule_progress_percent=excluded.schedule_progress_percent,
              cost_consumption_percent=excluded.cost_consumption_percent
            """, id,tenantId,projectId,date,t.budget(),t.actual(),t.committed(),t.etc(),pending,baseEac,exposureEac,variance,p.physical(),p.schedule(),costPct,userId);
        return jdbc.queryForObject("select id from control_forecast_snapshots where project_id=? and snapshot_date=?", UUID.class, projectId, date);
    }

    void replaceWarnings(UUID tenantId, UUID projectId, UUID snapshotId, List<WarningSeed> seeds) {
        jdbc.update("delete from early_warning_signals where forecast_snapshot_id=?", snapshotId);
        for (WarningSeed s : seeds) {
            jdbc.update("insert into early_warning_signals(tenant_id,project_id,forecast_snapshot_id,signal_code,severity,title,description,metric_value,threshold_value) values(?,?,?,?,?,?,?,?,?)",
                    tenantId,projectId,snapshotId,s.code(),s.severity(),s.title(),s.title(),s.metric(),s.threshold());
        }
    }

    List<UUID> consultantOrganizations(UUID tenantId, UUID projectId) {
        return jdbc.query("select distinct organization_id from project_participants where tenant_id=? and project_id=? and active=true and party_role='CONSULTANT'",
                (rs,n) -> rs.getObject(1,UUID.class), tenantId, projectId);
    }

    int dueDocuments(UUID tenantId, UUID projectId, UUID orgId) {
        Integer v = jdbc.queryForObject("select count(*) from documents where tenant_id=? and project_id=? and originator_org_id=? and due_at is not null",
                Integer.class, tenantId, projectId, orgId);
        return v == null ? 0 : v;
    }

    int overdueDocuments(UUID tenantId, UUID projectId, UUID orgId) {
        Integer v = jdbc.queryForObject("select count(*) from documents where tenant_id=? and project_id=? and originator_org_id=? and due_at<now() and status<>'APPROVED'",
                Integer.class, tenantId, projectId, orgId);
        return v == null ? 0 : v;
    }

    BigDecimal latestPartyForecast(UUID tenantId, UUID projectId, UUID orgId) {
        return jdbc.query("select forecast_final_cost from forecast_snapshots where tenant_id=? and project_id=? and source_organization_id=? order by snapshot_date desc,created_at desc limit 1",
                rs -> rs.next() ? rs.getBigDecimal(1) : null, tenantId, projectId, orgId);
    }

    void upsertConsultantKpi(UUID tenantId, UUID projectId, UUID orgId, LocalDate date, BigDecimal sla,
                             BigDecimal alignment, BigDecimal overall, int overdue, int due, BigDecimal party,
                             BigDecimal controlForecast, BigDecimal gap) {
        jdbc.update("""
            insert into consultant_kpi_snapshots(tenant_id,project_id,organization_id,snapshot_date,document_sla_health,forecast_alignment,overall_control_health,overdue_documents,due_documents,latest_party_forecast,control_forecast,forecast_gap)
            values(?,?,?,?,?,?,?,?,?,?,?,?) on conflict(project_id,organization_id,snapshot_date) do update set
            document_sla_health=excluded.document_sla_health,forecast_alignment=excluded.forecast_alignment,overall_control_health=excluded.overall_control_health,
            overdue_documents=excluded.overdue_documents,due_documents=excluded.due_documents,latest_party_forecast=excluded.latest_party_forecast,control_forecast=excluded.control_forecast,forecast_gap=excluded.forecast_gap
            """, tenantId,projectId,orgId,date,sla,alignment,overall,overdue,due,party,controlForecast,gap);
    }

    List<ForecastIntelligenceService.ForecastSnapshot> history(UUID tenantId, UUID projectId, int limit) {
        return jdbc.query("select id,snapshot_date,current_budget,actual_cost,committed_cost,estimate_to_complete,pending_variation_exposure,base_eac,exposure_eac,forecast_variance,physical_progress_percent,schedule_progress_percent,cost_consumption_percent from control_forecast_snapshots where tenant_id=? and project_id=? order by snapshot_date desc limit ?",
                (rs,n) -> new ForecastIntelligenceService.ForecastSnapshot(rs.getObject(1,UUID.class),rs.getDate(2).toLocalDate(),rs.getBigDecimal(3),rs.getBigDecimal(4),rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getBigDecimal(7),rs.getBigDecimal(8),rs.getBigDecimal(9),rs.getBigDecimal(10),rs.getBigDecimal(11),rs.getBigDecimal(12),rs.getBigDecimal(13)),
                tenantId, projectId, limit);
    }

    List<ForecastIntelligenceService.WarningView> warnings(UUID snapshotId) {
        return jdbc.query("select signal_code,severity,title,metric_value,threshold_value from early_warning_signals where forecast_snapshot_id=? order by case severity when 'CRITICAL' then 0 else 1 end",
                (rs,n) -> new ForecastIntelligenceService.WarningView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getBigDecimal(4),rs.getBigDecimal(5)), snapshotId);
    }

    List<ForecastIntelligenceService.KpiView> latestKpis(UUID tenantId, UUID projectId) {
        return jdbc.query("select k.organization_id,o.name,k.snapshot_date,k.document_sla_health,k.forecast_alignment,k.overall_control_health,k.overdue_documents,k.due_documents,k.latest_party_forecast,k.control_forecast,k.forecast_gap from consultant_kpi_snapshots k join organizations o on o.id=k.organization_id where k.tenant_id=? and k.project_id=? and k.snapshot_date=(select max(snapshot_date) from consultant_kpi_snapshots where tenant_id=? and project_id=?) order by k.overall_control_health asc",
                (rs,n) -> new ForecastIntelligenceService.KpiView(rs.getObject(1,UUID.class),rs.getString(2),rs.getDate(3).toLocalDate(),rs.getBigDecimal(4),rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getInt(7),rs.getInt(8),rs.getBigDecimal(9),rs.getBigDecimal(10),rs.getBigDecimal(11)),
                tenantId, projectId, tenantId, projectId);
    }

    record Totals(BigDecimal budget, BigDecimal actual, BigDecimal committed, BigDecimal etc) {}
    record Progress(BigDecimal physical, BigDecimal schedule) {}
    record WarningSeed(String code, String severity, String title, BigDecimal metric, BigDecimal threshold) {}
}
