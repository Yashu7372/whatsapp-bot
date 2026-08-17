package com.whatsappbot.resourcecost;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class ResourceCostRepository {
    private final JdbcTemplate jdbc;

    CostTotals totals(UUID tenantId, UUID projectId, UUID orgId) {
        String suffix = orgId == null ? "" : " and organization_id=?";
        Object[] args = orgId == null ? new Object[]{tenantId, projectId} : new Object[]{tenantId, projectId, orgId};
        BigDecimal labour = amount("select coalesce(sum(amount),0) from actual_cost_entries where tenant_id=? and project_id=? and source_type='TIMESHEET'" + suffix, args);
        BigDecimal equipment = amount("select coalesce(sum(amount),0) from actual_cost_entries where tenant_id=? and project_id=? and source_type='EQUIPMENT_USAGE'" + suffix, args);
        BigDecimal manual = amount("select coalesce(sum(amount),0) from actual_cost_entries where tenant_id=? and project_id=? and source_type='MANUAL'" + suffix, args);
        int resources = count("select count(*) from project_resources where tenant_id=? and project_id=? and active=true" + suffix, args);
        int pending = count("select count(*) from timesheets where tenant_id=? and project_id=? and status='SUBMITTED'" + suffix, args);
        return new CostTotals(labour, equipment, manual, resources, pending);
    }

    List<ResourceCostService.ResourceView> findResources(UUID tenantId, UUID projectId, UUID orgId) {
        String sql = """
            select r.id,r.organization_id,o.name,r.resource_type,r.resource_code,r.display_name,r.user_id,r.active
            from project_resources r join organizations o on o.id=r.organization_id
            where r.tenant_id=? and r.project_id=?
            """ + (orgId == null ? "" : " and r.organization_id=?") + " order by o.name,r.resource_type,r.display_name";
        Object[] args = orgId == null ? new Object[]{tenantId, projectId} : new Object[]{tenantId, projectId, orgId};
        return jdbc.query(sql, (rs,n) -> new ResourceCostService.ResourceView(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getBoolean(8)), args);
    }

    boolean isActiveProjectOrganization(UUID tenantId, UUID projectId, UUID orgId) {
        Integer count = jdbc.queryForObject("select count(*) from project_participants where tenant_id=? and project_id=? and organization_id=? and active=true",
            Integer.class, tenantId, projectId, orgId);
        return count != null && count > 0;
    }

    boolean validBudgetLine(UUID tenantId, UUID projectId, UUID budgetLineId) {
        if (budgetLineId == null) return true;
        Integer count = jdbc.queryForObject("""
            select count(*) from budget_lines b
             where b.id=? and b.tenant_id=? and b.project_id=?
               and not exists(select 1 from budget_lines child where child.parent_line_id=b.id)
            """, Integer.class, budgetLineId, tenantId, projectId);
        return count != null && count > 0;
    }

    void insertResource(UUID id, UUID tenantId, UUID projectId, UUID orgId, String type, String code, String displayName, UUID userId) {
        jdbc.update("insert into project_resources(id,tenant_id,project_id,organization_id,resource_type,resource_code,display_name,user_id) values(?,?,?,?,?,?,?,?)",
            id, tenantId, projectId, orgId, type, code, displayName, userId);
    }

    ResourceOwner findResourceOwner(UUID tenantId, UUID projectId, UUID resourceId) {
        return jdbc.query("select organization_id,resource_type from project_resources where id=? and tenant_id=? and project_id=? and active=true",
            rs -> rs.next() ? new ResourceOwner(rs.getObject(1, UUID.class), rs.getString(2)) : null,
            resourceId, tenantId, projectId);
    }

    void insertRate(UUID id, UUID tenantId, UUID projectId, UUID resourceId, String rateType, BigDecimal amount, String currency, LocalDate from, LocalDate to) {
        jdbc.update("insert into resource_rates(id,tenant_id,project_id,resource_id,rate_type,rate_amount,currency,effective_from,effective_to) values(?,?,?,?,?,?,?,?,?)",
            id, tenantId, projectId, resourceId, rateType, amount, currency, from, to);
    }

    RateRow findEffectiveRate(UUID resourceId, LocalDate date) {
        return jdbc.query("select rate_type,rate_amount,currency from resource_rates where resource_id=? and effective_from<=? and (effective_to is null or effective_to>=?) order by effective_from desc limit 1",
            rs -> rs.next() ? new RateRow(rs.getString(1), rs.getBigDecimal(2), rs.getString(3)) : null,
            resourceId, date, date);
    }

    void insertTimesheet(UUID id, UUID tenantId, UUID projectId, UUID orgId, UUID resourceId, LocalDate date, BigDecimal hours, String description, UUID createdBy, UUID documentId) {
        jdbc.update("insert into timesheets(id,tenant_id,project_id,organization_id,resource_id,work_date,hours,status,description,created_by,document_id) values(?,?,?,?,?,?,?,?,?,?,?)",
            id, tenantId, projectId, orgId, resourceId, date, hours, "SUBMITTED", description, createdBy, documentId);
    }

    TimesheetRow findTimesheet(UUID tenantId, UUID projectId, UUID timesheetId) {
        return jdbc.query("select resource_id,organization_id,work_date,hours,status from timesheets where id=? and tenant_id=? and project_id=?",
            rs -> rs.next() ? new TimesheetRow(rs.getObject(1,UUID.class), rs.getObject(2,UUID.class), rs.getDate(3).toLocalDate(), rs.getBigDecimal(4), rs.getString(5)) : null,
            timesheetId, tenantId, projectId);
    }

    /**
     * No rate/amount in this projection — this is the view a logger (any active project
     * participant, not just MANAGER/ADMIN) is allowed to see. Cost fields stay behind
     * {@link #findActualCosts}, which {@code commercialViewer} already restricts to MANAGER/ADMIN.
     */
    List<ResourceCostService.TimeLogView> findTimesheets(UUID tenantId, UUID projectId, UUID orgId, UUID documentId) {
        StringBuilder sql = new StringBuilder("""
            select t.id,t.resource_id,r.display_name,t.work_date,t.hours,t.status,t.document_id,t.description
            from timesheets t join project_resources r on r.id=t.resource_id
            where t.tenant_id=? and t.project_id=?
            """);
        List<Object> args = new java.util.ArrayList<>(List.of(tenantId, projectId));
        if (orgId != null) { sql.append(" and t.organization_id=?"); args.add(orgId); }
        if (documentId != null) { sql.append(" and t.document_id=?"); args.add(documentId); }
        sql.append(" order by t.work_date desc, t.created_at desc limit 200");
        return jdbc.query(sql.toString(), (rs,n) -> new ResourceCostService.TimeLogView(
            rs.getObject(1,UUID.class), rs.getObject(2,UUID.class), rs.getString(3), rs.getDate(4).toLocalDate(),
            rs.getBigDecimal(5), rs.getString(6), rs.getObject(7,UUID.class), rs.getString(8)), args.toArray());
    }

    void approveTimesheet(UUID timesheetId, UUID approvedBy) {
        jdbc.update("update timesheets set status='APPROVED',approved_by=?,approved_at=now(),updated_at=now() where id=?", approvedBy, timesheetId);
    }

    void insertActualCost(UUID tenantId, UUID projectId, UUID orgId, UUID budgetLineId, UUID resourceId, String sourceType,
                          UUID sourceId, LocalDate costDate, BigDecimal quantity, BigDecimal unitRate, BigDecimal amount,
                          String currency, String description) {
        jdbc.update("insert into actual_cost_entries(tenant_id,project_id,organization_id,budget_line_id,resource_id,source_type,source_id,cost_date,quantity,unit_rate,amount,currency,description) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            tenantId, projectId, orgId, budgetLineId, resourceId, sourceType, sourceId, costDate, quantity, unitRate, amount, currency, description);
    }

    void insertEquipmentUsage(UUID id, UUID tenantId, UUID projectId, UUID orgId, UUID resourceId, LocalDate date,
                              BigDecimal runningHours, BigDecimal quantity, String notes, UUID createdBy) {
        jdbc.update("insert into equipment_usage(id,tenant_id,project_id,organization_id,resource_id,usage_date,running_hours,quantity,status,notes,created_by) values(?,?,?,?,?,?,?,?,?,?,?)",
            id, tenantId, projectId, orgId, resourceId, date, runningHours, quantity, "APPROVED", notes, createdBy);
    }

    List<ResourceCostService.ActualCostView> findActualCosts(UUID tenantId, UUID projectId, UUID orgId, UUID documentId) {
        StringBuilder sql = new StringBuilder("""
            select a.id,a.organization_id,o.name,a.budget_line_id,b.cost_code,a.resource_id,r.display_name,a.source_type,a.cost_date,a.quantity,a.amount,a.currency,a.description
            from actual_cost_entries a join organizations o on o.id=a.organization_id
            left join budget_lines b on b.id=a.budget_line_id left join project_resources r on r.id=a.resource_id
            """);
        // Only TIMESHEET-sourced entries carry a document via the timesheet they were computed from
        // (actual_cost_entries itself has no document_id) — this join is a no-op unless documentId is set.
        if (documentId != null) {
            sql.append(" left join timesheets t on t.id=a.source_id and a.source_type='TIMESHEET' ");
        }
        sql.append(" where a.tenant_id=? and a.project_id=?");
        List<Object> args = new java.util.ArrayList<>(List.of(tenantId, projectId));
        if (orgId != null) { sql.append(" and a.organization_id=?"); args.add(orgId); }
        if (documentId != null) { sql.append(" and t.document_id=?"); args.add(documentId); }
        sql.append(" order by a.cost_date desc,a.created_at desc limit 200");
        return jdbc.query(sql.toString(), (rs,n) -> new ResourceCostService.ActualCostView(
            rs.getObject(1,UUID.class), rs.getObject(2,UUID.class), rs.getString(3), rs.getObject(4,UUID.class),
            rs.getString(5), rs.getObject(6,UUID.class), rs.getString(7), rs.getString(8), rs.getDate(9).toLocalDate(),
            rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getString(12), rs.getString(13)), args.toArray());
    }

    private BigDecimal amount(String sql, Object[] args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }

    private int count(String sql, Object[] args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    record CostTotals(BigDecimal labour, BigDecimal equipment, BigDecimal manual, int resources, int pendingTimesheets) {}
    record ResourceOwner(UUID organizationId, String resourceType) {}
    record RateRow(String rateType, BigDecimal amount, String currency) {}
    record TimesheetRow(UUID resourceId, UUID organizationId, LocalDate workDate, BigDecimal hours, String status) {}
}
