package com.whatsappbot.commitment;

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
class CommitmentRepository {
    private final JdbcTemplate jdbc;

    SummaryTotals summary(UUID tenantId, UUID projectId, UUID orgId) {
        String suffix = orgId == null ? "" : " and organization_id=?";
        Object[] args = orgId == null ? new Object[]{tenantId,projectId} : new Object[]{tenantId,projectId,orgId};
        return new SummaryTotals(
            amount("select coalesce(sum(original_amount+approved_changes),0) from project_commitments where tenant_id=? and project_id=? and status='ACTIVE'"+suffix,args),
            amount("select coalesce(sum(amount),0) from material_receipts where tenant_id=? and project_id=? and status='ACCEPTED'"+suffix,args),
            amount("select coalesce(sum(requested_amount),0) from project_variations where tenant_id=? and project_id=? and status in ('PROPOSED','UNDER_REVIEW')"+suffix,args),
            amount("select coalesce(sum(approved_amount),0) from project_variations where tenant_id=? and project_id=? and status='APPROVED'"+suffix,args));
    }

    List<CommitmentService.CommitmentView> findCommitments(UUID tenantId, UUID projectId, UUID orgId) {
        String sql="""
            select c.id,c.organization_id,o.name,c.budget_line_id,b.cost_code,c.commitment_type,c.reference_no,c.description,
                   c.original_amount,c.approved_changes,c.currency,c.status,c.start_date,c.end_date
              from project_commitments c join organizations o on o.id=c.organization_id
              left join budget_lines b on b.id=c.budget_line_id
             where c.tenant_id=? and c.project_id=?
            """+(orgId==null?"":" and c.organization_id=?")+" order by c.updated_at desc";
        Object[] args=orgId==null?new Object[]{tenantId,projectId}:new Object[]{tenantId,projectId,orgId};
        return jdbc.query(sql,(rs,n)->new CommitmentService.CommitmentView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getObject(4,UUID.class),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getBigDecimal(9),rs.getBigDecimal(10),rs.getBigDecimal(9).add(rs.getBigDecimal(10)),rs.getString(11),rs.getString(12),date(rs.getDate(13)),date(rs.getDate(14))),args);
    }

    void insertCommitment(UUID id, UUID tenantId, UUID projectId, UUID orgId, UUID budgetLineId, String type, String reference,
                          String description, BigDecimal original, BigDecimal changes, String currency, String status,
                          LocalDate startDate, LocalDate endDate, UUID createdBy) {
        jdbc.update("""
            insert into project_commitments(id,tenant_id,project_id,organization_id,budget_line_id,commitment_type,reference_no,description,original_amount,approved_changes,currency,status,start_date,end_date,created_by)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenantId,projectId,orgId,budgetLineId,type,reference,description,original,changes,currency,status,startDate,endDate,createdBy);
    }

    List<CommitmentService.MaterialReceiptView> findMaterials(UUID tenantId, UUID projectId, UUID orgId) {
        String sql="""
            select m.id,m.organization_id,o.name,m.commitment_id,c.reference_no,m.budget_line_id,b.cost_code,m.receipt_ref,m.material_code,m.description,m.receipt_date,m.quantity,m.unit,m.unit_cost,m.amount,m.currency,m.status,m.document_id
              from material_receipts m join organizations o on o.id=m.organization_id
              left join project_commitments c on c.id=m.commitment_id left join budget_lines b on b.id=m.budget_line_id
             where m.tenant_id=? and m.project_id=?
            """+(orgId==null?"":" and m.organization_id=?")+" order by m.receipt_date desc,m.created_at desc limit 300";
        Object[] args=orgId==null?new Object[]{tenantId,projectId}:new Object[]{tenantId,projectId,orgId};
        return jdbc.query(sql,(rs,n)->new CommitmentService.MaterialReceiptView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getObject(4,UUID.class),rs.getString(5),rs.getObject(6,UUID.class),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getDate(11).toLocalDate(),rs.getBigDecimal(12),rs.getString(13),rs.getBigDecimal(14),rs.getBigDecimal(15),rs.getString(16),rs.getString(17),rs.getObject(18,UUID.class)),args);
    }

    boolean commitmentBelongsTo(UUID commitmentId, UUID tenantId, UUID projectId, UUID orgId) {
        Integer count=jdbc.queryForObject("select count(*) from project_commitments where id=? and tenant_id=? and project_id=? and organization_id=?",Integer.class,commitmentId,tenantId,projectId,orgId);
        return count!=null && count>0;
    }

    void insertMaterial(UUID id, UUID tenantId, UUID projectId, UUID orgId, UUID commitmentId, UUID budgetLineId,
                        String receiptRef, String materialCode, String description, LocalDate receiptDate, BigDecimal quantity,
                        String unit, BigDecimal unitCost, BigDecimal amount, String currency, String status, UUID documentId, UUID createdBy) {
        jdbc.update("""
            insert into material_receipts(id,tenant_id,project_id,organization_id,commitment_id,budget_line_id,receipt_ref,material_code,description,receipt_date,quantity,unit,unit_cost,amount,currency,status,document_id,created_by)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenantId,projectId,orgId,commitmentId,budgetLineId,receiptRef,materialCode,description,receiptDate,quantity,unit,unitCost,amount,currency,status,documentId,createdBy);
    }

    List<CommitmentService.VariationView> findVariations(UUID tenantId, UUID projectId, UUID orgId) {
        String sql="""
            select v.id,v.organization_id,o.name,v.budget_line_id,b.cost_code,v.variation_ref,v.title,v.source_type,v.requested_amount,v.approved_amount,v.currency,v.status,v.source_document_id,v.submitted_at,v.approved_at
              from project_variations v left join organizations o on o.id=v.organization_id left join budget_lines b on b.id=v.budget_line_id
             where v.tenant_id=? and v.project_id=?
            """+(orgId==null?"":" and v.organization_id=?")+" order by v.updated_at desc";
        Object[] args=orgId==null?new Object[]{tenantId,projectId}:new Object[]{tenantId,projectId,orgId};
        return jdbc.query(sql,(rs,n)->new CommitmentService.VariationView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getObject(4,UUID.class),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getBigDecimal(9),rs.getBigDecimal(10),rs.getString(11),rs.getString(12),rs.getObject(13,UUID.class),rs.getTimestamp(14)==null?null:rs.getTimestamp(14).toLocalDateTime(),rs.getTimestamp(15)==null?null:rs.getTimestamp(15).toLocalDateTime()),args);
    }

    void insertVariation(UUID id, UUID tenantId, UUID projectId, UUID orgId, UUID budgetLineId, String ref, String title,
                         String description, String sourceType, UUID sourceDocumentId, BigDecimal requestedAmount,
                         String currency, String status, LocalDateTime submittedAt, UUID createdBy) {
        jdbc.update("""
            insert into project_variations(id,tenant_id,project_id,organization_id,budget_line_id,variation_ref,title,description,source_type,source_document_id,requested_amount,currency,status,submitted_at,created_by)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenantId,projectId,orgId,budgetLineId,ref,title,description,sourceType,sourceDocumentId,requestedAmount,currency,status,submittedAt,createdBy);
    }

    int approveVariation(UUID variationId, UUID tenantId, UUID projectId, BigDecimal approvedAmount, UUID approvedBy) {
        return jdbc.update("update project_variations set status='APPROVED',approved_amount=?,approved_at=now(),approved_by=?,updated_at=now() where id=? and tenant_id=? and project_id=? and status in ('PROPOSED','UNDER_REVIEW')",
                approvedAmount,approvedBy,variationId,tenantId,projectId);
    }

    boolean activeProjectOrganization(UUID tenantId, UUID projectId, UUID orgId) {
        Integer count=jdbc.queryForObject("select count(*) from project_participants where tenant_id=? and project_id=? and organization_id=? and active=true",Integer.class,tenantId,projectId,orgId);
        return count!=null && count>0;
    }

    boolean validBudgetLine(UUID tenantId, UUID projectId, UUID lineId) {
        Integer count=jdbc.queryForObject("select count(*) from budget_lines where id=? and tenant_id=? and project_id=?",Integer.class,lineId,tenantId,projectId);
        return count!=null && count>0;
    }

    private BigDecimal amount(String sql,Object[] args){
        BigDecimal value=jdbc.queryForObject(sql,BigDecimal.class,args);
        return value==null?BigDecimal.ZERO:value;
    }
    private static LocalDate date(java.sql.Date d){return d==null?null:d.toLocalDate();}

    record SummaryTotals(BigDecimal commitments, BigDecimal materials, BigDecimal pendingVariations, BigDecimal approvedVariations) {}
}
