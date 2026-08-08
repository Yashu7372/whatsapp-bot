package com.whatsappbot.commitment;

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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CommitmentService {
    private static final Set<String> COMMITMENT_TYPES = Set.of("PURCHASE_ORDER","SUBCONTRACT","OTHER");
    private static final Set<String> VARIATION_SOURCES = Set.of("SITE_INSTRUCTION","RFI","DESIGN_CHANGE","CLIENT_CHANGE","OTHER");

    private final CommitmentRepository repository;
    private final ProjectService projectService;
    private final ProjectAccessService accessService;

    @Transactional(readOnly = true)
    public CommercialFactSummary summary(UUID tenantId, UUID userId, UUID projectId) {
        TenantUserEntity actor = commercialViewer(tenantId,userId,projectId);
        UUID org = broad(actor,tenantId,projectId) ? null : actor.getOrganizationId();
        CommitmentRepository.SummaryTotals totals = repository.summary(tenantId, projectId, org);
        return new CommercialFactSummary(totals.commitments(), totals.materials(), totals.pendingVariations(),
                totals.approvedVariations(), org==null?"PROJECT":"ORGANIZATION");
    }

    @Transactional(readOnly = true)
    public List<CommitmentView> commitments(UUID tenantId,UUID userId,UUID projectId){
        TenantUserEntity actor=commercialViewer(tenantId,userId,projectId);
        UUID org = broad(actor,tenantId,projectId) ? null : actor.getOrganizationId();
        return repository.findCommitments(tenantId, projectId, org);
    }

    @Transactional
    public UUID createCommitment(UUID tenantId,UUID userId,UUID projectId,CreateCommitmentRequest req){
        TenantUserEntity actor=commercialEditor(tenantId,userId,projectId);
        String type=req.commitmentType()==null?"PURCHASE_ORDER":req.commitmentType().toUpperCase();
        if(!COMMITMENT_TYPES.contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported commitment type");
        UUID org=req.organizationId()!=null?req.organizationId():actor.getOrganizationId();
        requireProjectOrganization(tenantId,projectId,org);
        if(!broad(actor,tenantId,projectId)&&!org.equals(actor.getOrganizationId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot create another organization's commitment");
        validateBudgetLine(tenantId,projectId,req.budgetLineId());
        UUID id=UUID.randomUUID();
        repository.insertCommitment(id,tenantId,projectId,org,req.budgetLineId(),type,req.referenceNo(),req.description(),
                money(req.originalAmount()),money(req.approvedChanges()),currency(req.currency()),
                req.status()==null?"ACTIVE":req.status().toUpperCase(),req.startDate(),req.endDate(),userId);
        return id;
    }

    @Transactional(readOnly = true)
    public List<MaterialReceiptView> materials(UUID tenantId,UUID userId,UUID projectId){
        TenantUserEntity actor=commercialViewer(tenantId,userId,projectId);
        UUID org = broad(actor,tenantId,projectId) ? null : actor.getOrganizationId();
        return repository.findMaterials(tenantId, projectId, org);
    }

    @Transactional
    public UUID recordMaterial(UUID tenantId,UUID userId,UUID projectId,CreateMaterialReceiptRequest req){
        TenantUserEntity actor=commercialEditor(tenantId,userId,projectId);
        UUID org=req.organizationId()!=null?req.organizationId():actor.getOrganizationId();
        requireProjectOrganization(tenantId,projectId,org);
        if(!broad(actor,tenantId,projectId)&&!org.equals(actor.getOrganizationId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot record another organization's material");
        validateBudgetLine(tenantId,projectId,req.budgetLineId());
        if(req.commitmentId()!=null && !repository.commitmentBelongsTo(req.commitmentId(),tenantId,projectId,org))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Commitment does not belong to this organization/project");

        BigDecimal qty=money(req.quantity());
        BigDecimal unitCost=money(req.unitCost());
        BigDecimal amount=req.amount()==null?qty.multiply(unitCost):req.amount();
        if(amount.compareTo(BigDecimal.ZERO)<0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Material amount cannot be negative");

        UUID id=UUID.randomUUID();
        repository.insertMaterial(id,tenantId,projectId,org,req.commitmentId(),req.budgetLineId(),req.receiptRef(),req.materialCode(),
                req.description(),req.receiptDate()==null?LocalDate.now():req.receiptDate(),qty,req.unit(),unitCost,amount,
                currency(req.currency()),req.status()==null?"ACCEPTED":req.status().toUpperCase(),req.documentId(),userId);
        return id;
    }

    @Transactional(readOnly = true)
    public List<VariationView> variations(UUID tenantId,UUID userId,UUID projectId){
        TenantUserEntity actor=commercialViewer(tenantId,userId,projectId);
        UUID org = broad(actor,tenantId,projectId) ? null : actor.getOrganizationId();
        return repository.findVariations(tenantId, projectId, org);
    }

    @Transactional
    public UUID createVariation(UUID tenantId,UUID userId,UUID projectId,CreateVariationRequest req){
        TenantUserEntity actor=commercialEditor(tenantId,userId,projectId);
        UUID org=req.organizationId()!=null?req.organizationId():actor.getOrganizationId();
        if(org!=null) requireProjectOrganization(tenantId,projectId,org);
        if(!broad(actor,tenantId,projectId) && (org==null || !org.equals(actor.getOrganizationId())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot create another organization's variation");
        validateBudgetLine(tenantId,projectId,req.budgetLineId());
        String source=req.sourceType()==null?"OTHER":req.sourceType().toUpperCase();
        if(!VARIATION_SOURCES.contains(source)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported variation source");

        UUID id=UUID.randomUUID();
        repository.insertVariation(id,tenantId,projectId,org,req.budgetLineId(),req.variationRef(),req.title(),req.description(),source,
                req.sourceDocumentId(),money(req.requestedAmount()),currency(req.currency()),
                req.status()==null?"PROPOSED":req.status().toUpperCase(),LocalDateTime.now(),userId);
        return id;
    }

    @Transactional
    public void approveVariation(UUID tenantId,UUID userId,UUID projectId,UUID variationId,BigDecimal approvedAmount){
        TenantUserEntity actor=commercialEditor(tenantId,userId,projectId);
        if(!broad(actor,tenantId,projectId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only client/consultant commercial roles can approve project variations");
        int updated=repository.approveVariation(variationId,tenantId,projectId,money(approvedAmount),userId);
        if(updated==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Variation is not awaiting approval");
    }

    private TenantUserEntity commercialViewer(UUID tenantId,UUID userId,UUID projectId){
        projectService.get(tenantId,userId,projectId);
        TenantUserEntity actor=accessService.requireActiveUser(tenantId,userId);
        if(accessService.isTenantAdministrator(actor)) return actor;
        if(actor.getRole()!=UserRole.MANAGER&&actor.getRole()!=UserRole.ADMIN)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Commercial data requires manager or administrator access");
        return actor;
    }

    private TenantUserEntity commercialEditor(UUID tenantId,UUID userId,UUID projectId){return commercialViewer(tenantId,userId,projectId);}

    private boolean broad(TenantUserEntity actor,UUID tenantId,UUID projectId){
        if(accessService.isTenantAdministrator(actor)) return true;
        List<PartyRole> roles=accessService.rolesOnProject(tenantId,projectId,actor);
        return roles.contains(PartyRole.CLIENT)||roles.contains(PartyRole.CONSULTANT);
    }

    private void requireProjectOrganization(UUID tenantId,UUID projectId,UUID org){
        if(org==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Organization is required");
        if(!repository.activeProjectOrganization(tenantId,projectId,org))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Organization is not active on this project");
    }

    private void validateBudgetLine(UUID tenantId,UUID projectId,UUID line){
        if(line==null) return;
        if(!repository.validBudgetLine(tenantId,projectId,line))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Budget line does not belong to this project");
    }

    private static BigDecimal money(BigDecimal v){return v==null?BigDecimal.ZERO:v;}
    private static String currency(String c){return c==null||c.isBlank()?"AED":c.toUpperCase();}

    public record CommercialFactSummary(BigDecimal activeCommitments,BigDecimal acceptedMaterialActual,BigDecimal pendingVariationExposure,BigDecimal approvedVariations,String visibilityScope){}
    public record CommitmentView(UUID id,UUID organizationId,String organizationName,UUID budgetLineId,String costCode,String commitmentType,String referenceNo,String description,BigDecimal originalAmount,BigDecimal approvedChanges,BigDecimal currentAmount,String currency,String status,LocalDate startDate,LocalDate endDate){}
    public record MaterialReceiptView(UUID id,UUID organizationId,String organizationName,UUID commitmentId,String commitmentReference,UUID budgetLineId,String costCode,String receiptRef,String materialCode,String description,LocalDate receiptDate,BigDecimal quantity,String unit,BigDecimal unitCost,BigDecimal amount,String currency,String status,UUID documentId){}
    public record VariationView(UUID id,UUID organizationId,String organizationName,UUID budgetLineId,String costCode,String variationRef,String title,String sourceType,BigDecimal requestedAmount,BigDecimal approvedAmount,String currency,String status,UUID sourceDocumentId,LocalDateTime submittedAt,LocalDateTime approvedAt){}
    public record CreateCommitmentRequest(UUID organizationId,UUID budgetLineId,String commitmentType,String referenceNo,String description,BigDecimal originalAmount,BigDecimal approvedChanges,String currency,String status,LocalDate startDate,LocalDate endDate){}
    public record CreateMaterialReceiptRequest(UUID organizationId,UUID commitmentId,UUID budgetLineId,String receiptRef,String materialCode,String description,LocalDate receiptDate,BigDecimal quantity,String unit,BigDecimal unitCost,BigDecimal amount,String currency,String status,UUID documentId){}
    public record CreateVariationRequest(UUID organizationId,UUID budgetLineId,String variationRef,String title,String description,String sourceType,UUID sourceDocumentId,BigDecimal requestedAmount,String currency,String status){}
}
