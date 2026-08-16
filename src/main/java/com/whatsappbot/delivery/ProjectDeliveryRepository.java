package com.whatsappbot.delivery;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class ProjectDeliveryRepository {

    private final JdbcTemplate jdbc;

    String accountName(UUID tenantId) {
        return jdbc.query("select business_name from tenants where id=?",
                rs -> rs.next() ? rs.getString(1) : "Enterprise Account", tenantId);
    }

    ProjectMetrics metrics(UUID tenantId, UUID projectId) {
        BigDecimal progress = decimal("select coalesce(avg(progress_percent),0) from work_items where tenant_id=? and project_id=?", tenantId, projectId);
        BigDecimal actual = decimal("select coalesce(sum(amount),0) from actual_cost_entries where tenant_id=? and project_id=?", tenantId, projectId);
        BigDecimal hours = decimal("select coalesce(sum(hours),0) from timesheets where tenant_id=? and project_id=?", tenantId, projectId);
        int open = count("select count(*) from work_items where tenant_id=? and project_id=? and status not in ('COMPLETED','CLOSED','CANCELLED')", tenantId, projectId);
        int blocked = count("select count(*) from work_items where tenant_id=? and project_id=? and status='BLOCKED'", tenantId, projectId);
        int overdue = count("select count(*) from documents where tenant_id=? and project_id=? and due_at<now() and status not in ('APPROVED','PUBLISHED','ARCHIVED')", tenantId, projectId);
        int pending = count("select count(*) from document_approvals a join documents d on d.id=a.document_id where a.tenant_id=? and d.project_id=? and a.status='PENDING'", tenantId, projectId);
        int participants = count("select count(*) from project_participants where tenant_id=? and project_id=? and active=true", tenantId, projectId);
        int stages = count("select count(*) from project_stages where tenant_id=? and project_id=?", tenantId, projectId);
        int completedStages = count("select count(*) from project_stages where tenant_id=? and project_id=? and status='COMPLETED'", tenantId, projectId);
        return new ProjectMetrics(progress, actual, open, blocked, overdue, pending, participants, stages, completedStages, hours);
    }

    BigDecimal actualCost(UUID tenantId, UUID projectId, UUID organizationId) {
        return decimal("select coalesce(sum(amount),0) from actual_cost_entries where tenant_id=? and project_id=? and organization_id=?",
                tenantId, projectId, organizationId);
    }

    BigDecimal organizationContractValue(UUID tenantId, UUID projectId, UUID organizationId) {
        return decimal("select coalesce(sum(c.original_value+c.approved_variations),0) from project_contracts c " +
                        "join project_participants p on p.id=c.participant_id " +
                        "where c.tenant_id=? and c.project_id=? and p.organization_id=? and c.status<>'CANCELLED'",
                tenantId, projectId, organizationId);
    }

    List<ParticipantRow> participants(UUID tenantId, UUID projectId) {
        String sql = "select p.id,p.organization_id,o.name,o.org_code,p.party_role,p.parent_participant_id," +
                " (select count(*) from tenant_users u where u.tenant_id=p.tenant_id and u.organization_id=p.organization_id and u.active=true) staff_count" +
                " from project_participants p join organizations o on o.id=p.organization_id" +
                " where p.tenant_id=? and p.project_id=? and p.active=true" +
                " order by case p.party_role when 'CLIENT' then 1 when 'CONSULTANT' then 2 when 'CONTRACTOR' then 3 else 4 end,o.name";
        return jdbc.query(sql, (rs, n) -> new ParticipantRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class), rs.getInt(7)), tenantId, projectId);
    }

    List<StageRow> stages(UUID tenantId, UUID projectId) {
        String sql = "select id,stage_code,name,stage_type,sequence_no,status,progress_percent,planned_start,planned_end,actual_start,actual_end" +
                " from project_stages where tenant_id=? and project_id=? order by sequence_no";
        return jdbc.query(sql, (rs, n) -> new StageRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getInt(5), rs.getString(6), rs.getBigDecimal(7), date(rs, 8), date(rs, 9), date(rs, 10), date(rs, 11)), tenantId, projectId);
    }

    List<PackageRow> packages(UUID tenantId, UUID projectId, UUID stageId) {
        String sql = "select id,package_code,name,discipline,status from work_packages" +
                " where tenant_id=? and project_id=? and stage_id=? order by sort_order,package_code";
        return jdbc.query(sql, (rs, n) -> new PackageRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5)), tenantId, projectId, stageId);
    }

    List<WorkItemRow> workItems(UUID tenantId, UUID projectId, UUID packageId) {
        String sql = "select w.id,w.item_code,w.name,w.work_type,w.status,w.priority,w.progress_percent," +
                " w.responsible_organization_id,o.name,w.budget_line_id,w.budget_amount," +
                " coalesce((select sum(a.amount) from actual_cost_entries a where a.tenant_id=w.tenant_id and a.project_id=w.project_id and a.work_item_id=w.id),0) actual_cost," +
                " coalesce((select sum(t.hours) from timesheets t where t.tenant_id=w.tenant_id and t.project_id=w.project_id and t.work_item_id=w.id),0) total_hours," +
                " (select count(*) from documents d where d.tenant_id=w.tenant_id and d.project_id=w.project_id and d.work_item_id=w.id) document_count," +
                " (select count(*) from documents d where d.tenant_id=w.tenant_id and d.project_id=w.project_id and d.work_item_id=w.id and d.status not in ('APPROVED','PUBLISHED','ARCHIVED')) pending_document_count," +
                " w.blocked_reason,w.planned_start,w.planned_end,w.actual_start,w.actual_end" +
                " from work_items w left join organizations o on o.id=w.responsible_organization_id" +
                " where w.tenant_id=? and w.project_id=? and w.package_id=? order by w.sort_order,w.item_code";
        return jdbc.query(sql, (rs, n) -> new WorkItemRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getBigDecimal(7), rs.getObject(8, UUID.class),
                rs.getString(9), rs.getObject(10, UUID.class), nz(rs.getBigDecimal(11)), nz(rs.getBigDecimal(12)),
                nz(rs.getBigDecimal(13)), rs.getInt(14), rs.getInt(15), rs.getString(16), date(rs, 17), date(rs, 18),
                date(rs, 19), date(rs, 20)), tenantId, projectId, packageId);
    }

    List<AssignmentRow> assignments(UUID tenantId, UUID workItemId) {
        String sql = "select a.user_id,u.full_name,u.job_title,u.department,u.role,o.name,a.responsibility" +
                " from work_item_assignments a join tenant_users u on u.id=a.user_id join organizations o on o.id=a.organization_id" +
                " where a.tenant_id=? and a.work_item_id=? and a.active=true and u.active=true order by a.sort_order,u.full_name";
        return jdbc.query(sql, (rs, n) -> new AssignmentRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)), tenantId, workItemId);
    }

    List<DocumentRow> documents(UUID tenantId, UUID workItemId) {
        String sql = "select id,document_code,title,doc_type,status,current_revision_code,review_outcome,due_at,approved_value" +
                " from documents where tenant_id=? and work_item_id=? order by updated_at desc limit 25";
        return jdbc.query(sql, (rs, n) -> new DocumentRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), timestamp(rs, 8), nz(rs.getBigDecimal(9))),
                tenantId, workItemId);
    }

    private BigDecimal decimal(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return nz(value);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static BigDecimal nz(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static LocalDate date(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        java.sql.Date value = rs.getDate(index); return value == null ? null : value.toLocalDate();
    }
    private static LocalDateTime timestamp(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(index); return value == null ? null : value.toLocalDateTime();
    }

    record ProjectMetrics(BigDecimal progressPercent, BigDecimal actualCost, int openWorkItems, int blockedWorkItems,
                          int overdueDocuments, int pendingApprovals, int participantCount, int stageCount,
                          int completedStages, BigDecimal totalHours) {}
    record ParticipantRow(UUID id, UUID organizationId, String organizationName, String organizationCode,
                          String partyRole, UUID parentParticipantId, int staffCount) {}
    record StageRow(UUID id, String stageCode, String name, String stageType, int sequenceNo, String status,
                    BigDecimal progressPercent, LocalDate plannedStart, LocalDate plannedEnd, LocalDate actualStart, LocalDate actualEnd) {}
    record PackageRow(UUID id, String packageCode, String name, String discipline, String status) {}
    record WorkItemRow(UUID id, String itemCode, String name, String workType, String status, String priority,
                       BigDecimal progressPercent, UUID responsibleOrganizationId, String responsibleOrganizationName,
                       UUID budgetLineId, BigDecimal budgetAmount, BigDecimal actualCost, BigDecimal totalHours,
                       int documentCount, int pendingDocumentCount, String blockedReason, LocalDate plannedStart,
                       LocalDate plannedEnd, LocalDate actualStart, LocalDate actualEnd) {}
    record AssignmentRow(UUID userId, String fullName, String jobTitle, String department, String accessRole,
                         String organizationName, String responsibility) {}
    record DocumentRow(UUID id, String documentCode, String title, String docType, String status,
                       String revisionCode, String reviewOutcome, LocalDateTime dueAt, BigDecimal approvedValue) {}
}
