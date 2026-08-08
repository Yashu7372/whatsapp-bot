package com.whatsappbot.commitment;

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
public class CommitmentService {
    private static final Set<String> COMMITMENT_TYPES = Set.of("PURCHASE_ORDER","SUBCONTRACT","OTHER");
    private static final Set<String> VARIATION_SOURCES = Set.of("SITE_INSTRUCTION","RFI","DESIGN_CHANGE","CLIENT_CHANGE","OTHER");

    private final JdbcTemplate jdbc;
    private final ProjectService projectService;
    private final ProjectAccessService accessService;

    @Transactional(readOnly = true)
    public CommercialFactSummary summary(UUID tenantId, UUID userId, UUID projectId) {
        TenantUserEntity actor = commercialViewer(tenantId,userId,projectId);
        UUID org = broad(actor,tenantId,projectId) ? null : actor.getOrganizationId();
        String suffix = org == null ? "" : " and organization_id=?";
        Object[] args = org == null ? new Object[]{tenantId,projectId} : new Object[]{tenantId,projectId,org};
        BigDecimal commitments = amount("select coalesce(sum(original_amount+approved_changes),0) from project_commitments where tenant_id=? and project_id=? and status='ACTIVE'"+suffix,args);
        BigDecimal materials = amount("select coalesce(sum(amount),0) from material_receipts where tenant_id=? and project_id=? and status='ACCEPTED'"+suffix,args);
        BigDecimal pendingVariations = amount("select coalesce(sum(requested_amount),0) from project_variations where tenant_id=? and project_id=? and status in ('PROPOSED','UNDER_REVIEW')"+suffix,args);
        BigDecimal approvedVariations = amount("select coalesce(sum(approved_amount),0) from project_variations where tenant_id=? and project_id=? and status='APPROVED'"+suffix,args);
        return new CommercialFactSummary(commitments,materials,pendingVariations,approvedVariations,org==null?"PROJECT":"ORGANIZATION");
    }

    @Transactional(readOnly = true)
    public List<CommitmentView> commitments(UUID tenantId,UUID userId,UUID projectId){
        TenantUserEntity actor=commercialViewer(tenantId,userId,projectId); boolean broad=broad(actor,tenantId,projectId);
        String sql="""
            select c.id,c.organization_id,o.name,c.budget_line_id,b.cost_code,c.commitment_type,c.reference_no,c.description,
                   c.original_amount,c.approved_changes,c.currency,c.status,c.start_date,c.end_date
              from project_commitments c join organizations o on o.id=c.organization_id
              left join budget_lines b on b.id=c.budget_line_id
             where c.tenant_id=? and c.project_id=?
            """+(broad?"":" and c.organization_id=?")+" order by c.updated_at desc";
        Object[] args=broad?new Object[]{tenantId,projectId}:new Object[]{tenantId,projectId,actor.getOrganizationId()};
        return jdbc.query(sql,(rs,n)->new CommitmentView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getObject(4,UUID.class),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getBigDecimal(9),rs.getBigDecimal(10),rs.getBigDecimal(9).add(rs.getBigDecimal(10)),rs.getString(11),rs.getString(12),date(rs.getDate(13)),date(rs.getDate(14))),args);
    }

    @Transactional
    public UUID createCommitment(UUID tenantId,UUID userId,UUID projectId,CreateCommitmentRequest req){
        TenantUserEntity actor=commercialEditor(tenantId,userId,projectId);
        String type=req.commitmentType()==null?"PURCHASE_ORDER":req.commitmentType().toUpperCase();
        if(!COMMITMENT_TYPES.contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported commitment type");
        UUID org=req.organizationId()!=null?req.organizationId():actor.getOrganizationId();
        requireProjectOrganization(tenantId,projectId,org);
        if(!broad(actor,tenantId,projectId)&&!org.equals(actor.getOrganizationId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot create another organization's commitment");
        validateBudgetLine(tenantId,projectId,req.budgetLineId());
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into project_commitments(id,tenant_id,project_id,organization_id,budget_line_id,commitment_type,reference_no,description,original_amount,approved_changes,currency,status,start_date,end_date,created_by)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenantId,projectId,org,req.budgetLineId(),type,req.referenceNo(),req.description(),money(req.originalAmount()),money(req.approvedChanges()),currency(req.currency()),req.status()==null?"ACTIVE":req.status().toUpperCase(),req.startDate(),req.endDate(),userId);
        return id;
    }

    @Transactional(readOnly = true)
    public List<MaterialReceiptView> materials(UUID tenantId,UUID userId,UUID projectId){
        TenantUserEntity actor=commercialViewer(tenantId,userId,projectId); boolean broad=broad(actor,tenantId,projectId);
        String sql="""
            select m.id,m.organization_id,o.name,m.commitment_id,c.reference_no,m.budget_line_id,b.cost_code,m.receipt_ref,m.material_code,m.description,m.receipt_date,m.quantity,m.unit,m.unit_cost,m.amount,m.currency,m.status,m.document_id
              from material_receipts m join organizations o on o.id=m.organization_id
              left join project_commitments c on c.id=m.commitment_id left join budget_lines b on b.id=m.budget_line_id
             where m.tenant_id=? and m.project_id=?
            """+(broad?"":" and m.organization_id=?")+" order by m.receipt_date desc,m.created_at desc limit 300";
        Object[] args=broad?new Object[]{tenantId,projectId}:new Object[]{tenantId,projectId,actor.getOrganizationId()};
        return jdbc.query(sql,(rs,n)->new MaterialReceiptView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getObject(4,UUID.class),rs.getString(5),rs.getObject(6,UUID.class),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getDate(11).toLocalDate(),rs.getBigDecimal(12),rs.getString(13),rs.getBigDecimal(14),rs.getBigDecimal(15),rs.getString(16),rs.getString(17),rs.getObject(18,UUID.class)),args);
    }

    @Transactional
    public UUID recordMaterial(UUID tenantId,UUID userId,UUID projectId,CreateMaterialReceiptRequest req){
        TenantUserEntity actor=commercialEditor(tenantId,userId,projectId);
        UUID org=req.organizationId()!=null?req.organizationId():actor.getOrganizationId();
        requireProjectOrganization(tenantId,projectId,org);
        if(!broad(actor,tenantId,projectId)&&!org.equals(actor.getOrganizationId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot record another organization's material");
        validateBudgetLine(tenantId,projectId,req.budgetLineId());
        if(req.commitmentId()!=null){Integer c=jdbc.queryForObject("select count(*) from project_commitments where id=? and tenant_id=? and project_id=? and organization_id=?",Integer.class,req.commitmentId(),tenantId,projectId,org);if(c==null||c==0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Commitment does not belong to this organization/project");}
        BigDecimal qty=money(req.quantity()), unitCost=money(req.unitCost());
        BigDecimal calculated=qty.multiply(unitCost);
        BigDecimal amount=req.amount()==null?calculated:req.amount();
        if(amount.compareTo(BigDecimal.ZERO)<0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Material amount cannot be negative");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into material_receipts(id,tenant_id,project_id,organization_id,commitment_id,budget_line_id,receipt_ref,material_code,description,receipt_date,quantity,unit,unit_cost,amount,currency,status,document_id,created_by)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenantId,projectId,org,req.commitmentId(),req.budgetLineId(),req.receiptRef(),req.materialCode(),req.description(),req.receiptDate()==null?LocalDate.now():req.receiptDate(),qty,req.unit(),unitCost,amount,currency(req.currency()),req.status()==null?"ACCEPTED":req.status().toUpperCase(),req.documentId(),userId);
        return id;
    }

    @Transactional(readOnly = true)
    public List<VariationView> variations(UUID tenantId,UUID userId,UUID projectId){
        TenantUserEntity actor=commercialViewer(tenantId,userId,projectId); boolean broad=broad(actor,tenantId,projectId);
        String sql="""
            select v.id,v.organization_id,o.name,v.budget_line_id,b.cost_code,v.variation_ref,v.title,v.source_type,v.requested_amount,v.approved_amount,v.currency,v.status,v.source_document_id,v.submitted_at,v.approved_at
              from project_variations v left join organizations o on o.id=v.organization_id left join budget_lines b on b.id=v.budget_line_id
             where v.tenant_id=? and v.project_id=?
            """+(broad?"":" and v.organization_id=?")+" order by v.updated_at desc";
        Object[] args=broad?new Object[]{tenantId,projectId}:new Object[]{tenantId,projectId,actor.getOrganizationId()};
        return jdbc.query(sql,(rs,n)->new VariationView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getObject(4,UUID.class),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getBigDecimal(9),rs.getBigDecimal(10),rs.getString(11),rs.getString(12),rs.getObject(13,UUID.class),rs.getTimestamp(14)==null?null:rs.getTimestamp(14).toLocalDateTime(),rs.getTimestamp(15)==null?null:rs.getTimestamp(15).toLocalDateTime()),args);
    }

    @Transactional
    public UUID createVariation(UUID tenantId,UUID userId,UUID projectId,CreateVariationRequest req){
        TenantUserEntity actor=commercialEditor(tenantId,userId,projectId);
        UUID org=req.organizationId()!=null?req.organizationId():actor.getOrganizationId();
        if(org!=null)requireProjectOrganization(tenantId,projectId,org);
        if(!broad(actor,tenantId,projectId)&&!org.equals(actor.getOrganizationId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot create another organization's variation");
        validateBudgetLine(tenantId,projectId,req.budgetLineId());
        String source=req.sourceType()==null?"OTHER":req.sourceType().toUpperCase();
        if(!VARIATION_SOURCES.contains(source)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported variation source");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into project_variations(id,tenant_id,project_id,organization_id,budget_line_id,variation_ref,title,description,source_type,source_document_id,requested_amount,currency,status,submitted_at,created_by)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenantId,projectId,org,req.budgetLineId(),req.variationRef(),req.title(),req.description(),source,req.sourceDocumentId(),money(req.requestedAmount()),currency(req.currency()),req.status()==null?"PROPOSED":req.status().toUpperCase(),java.time.LocalDateTime.now(),userId);
        return id;
    }

    @Transactional
    public void approveVariation(UUID tenantId,UUID userId,UUID projectId,UUID variationId,BigDecimal approvedAmount){
        TenantUserEntity actor=commercialEditor(tenantId,userId,projectId);
        if(!broad(actor,tenantId,projectId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only client/consultant commercial roles can approve project variations");
        int updated=jdbc.update("update project_variations set status='APPROVED',approved_amount=?,approved_at=now(),approved_by=?,updated_at=now() where id=? and tenant_id=? and project_id=? and status in ('PROPOSED','UNDER_REVIEW')",money(approvedAmount),userId,variationId,tenantId,projectId);
        if(updated==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Variation is not awaiting approval");
    }

    private TenantUserEntity commercialViewer(UUID tenantId,UUID userId,UUID projectId){projectService.get(tenantId,userId,projectId);TenantUserEntity actor=accessService.requireActiveUser(tenantId,userId);if(accessService.isTenantAdministrator(actor))return actor;if(actor.getRole()!=UserRole.MANAGER&&actor.getRole()!=UserRole.ADMIN)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Commercial data requires manager or administrator access");return actor;}
    private TenantUserEntity commercialEditor(UUID tenantId,UUID userId,UUID projectId){return commercialViewer(tenantId,userId,projectId);}
    private boolean broad(TenantUserEntity actor,UUID tenantId,UUID projectId){if(accessService.isTenantAdministrator(actor))return true;List<PartyRole> roles=accessService.rolesOnProject(tenantId,projectId,actor);return roles.contains(PartyRole.CLIENT)||roles.contains(PartyRole.CONSULTANT);}
    private void requireProjectOrganization(UUID tenantId,UUID projectId,UUID org){if(org==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Organization is required");Integer c=jdbc.queryForObject("select count(*) from project_participants where tenant_id=? and project_id=? and organization_id=? and active=true",Integer.class,tenantId,projectId,org);if(c==null||c==0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Organization is not active on this project");}
    private void validateBudgetLine(UUID tenantId,UUID projectId,UUID line){if(line==null)return;Integer c=jdbc.queryForObject("select count(*) from budget_lines where id=? and tenant_id=? and project_id=?",Integer.class,line,tenantId,projectId);if(c==null||c==0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Budget line does not belong to this project");}
    private BigDecimal amount(String sql,Object[] args){BigDecimal v=jdbc.queryForObject(sql,BigDecimal.class,args);return money(v);} private static BigDecimal money(BigDecimal v){return v==null?BigDecimal.ZERO:v;} private static String currency(String c){return c==null||c.isBlank()?"AED":c.toUpperCase();} private static LocalDate date(java.sql.Date d){return d==null?null:d.toLocalDate();}

    public record CommercialFactSummary(BigDecimal activeCommitments,BigDecimal acceptedMaterialActual,BigDecimal pendingVariationExposure,BigDecimal approvedVariations,String visibilityScope){}
    public record CommitmentView(UUID id,UUID organizationId,String organizationName,UUID budgetLineId,String costCode,String commitmentType,String referenceNo,String description,BigDecimal originalAmount,BigDecimal approvedChanges,BigDecimal currentAmount,String currency,String status,LocalDate startDate,LocalDate endDate){}
    public record MaterialReceiptView(UUID id,UUID organizationId,String organizationName,UUID commitmentId,String commitmentReference,UUID budgetLineId,String costCode,String receiptRef,String materialCode,String description,LocalDate receiptDate,BigDecimal quantity,String unit,BigDecimal unitCost,BigDecimal amount,String currency,String status,UUID documentId){}
    public record VariationView(UUID id,UUID organizationId,String organizationName,UUID budgetLineId,String costCode,String variationRef,String title,String sourceType,BigDecimal requestedAmount,BigDecimal approvedAmount,String currency,String status,UUID sourceDocumentId,java.time.LocalDateTime submittedAt,java.time.LocalDateTime approvedAt){}
    public record CreateCommitmentRequest(UUID organizationId,UUID budgetLineId,String commitmentType,String referenceNo,String description,BigDecimal originalAmount,BigDecimal approvedChanges,String currency,String status,LocalDate startDate,LocalDate endDate){}
    public record CreateMaterialReceiptRequest(UUID organizationId,UUID commitmentId,UUID budgetLineId,String receiptRef,String materialCode,String description,LocalDate receiptDate,BigDecimal quantity,String unit,BigDecimal unitCost,BigDecimal amount,String currency,String status,UUID documentId){}
    public record CreateVariationRequest(UUID organizationId,UUID budgetLineId,String variationRef,String title,String description,String sourceType,UUID sourceDocumentId,BigDecimal requestedAmount,String currency,String status){}
}
