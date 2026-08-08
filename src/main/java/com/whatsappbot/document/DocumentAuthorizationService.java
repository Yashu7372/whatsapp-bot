package com.whatsappbot.document;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.UserRole;
import com.whatsappbot.project.PartyRole;
import com.whatsappbot.project.ProjectAccessService;
import com.whatsappbot.project.ProjectAuthorizationService;
import com.whatsappbot.project.ProjectPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Applies project participation, organization ownership, classification, grants and workflow assignment. */
@Service
@RequiredArgsConstructor
public class DocumentAuthorizationService {
    public static final String VIEW = "VIEW";
    public static final String EDIT = "EDIT";
    public static final String ISSUE = "ISSUE";

    private final DocumentAuthorizationRepository repository;
    private final ProjectAccessService accessService;
    private final ProjectAuthorizationService projectAuthorization;

    @Transactional(readOnly = true)
    public void requireView(UUID tenantId, UUID userId, UUID documentId) { evaluate(tenantId,userId,documentId,VIEW,false); }
    @Transactional(readOnly = true)
    public void requireEdit(UUID tenantId, UUID userId, UUID documentId) { evaluate(tenantId,userId,documentId,EDIT,true); }
    @Transactional(readOnly = true)
    public void requireIssue(UUID tenantId, UUID userId, UUID documentId) { evaluate(tenantId,userId,documentId,ISSUE,true); }

    /** Enforces the current workflow step's contractual authority before DocumentService mutates it. */
    @Transactional(readOnly = true)
    public void requireApprovalDecision(UUID tenantId, UUID userId, UUID approvalId) {
        DocumentAuthorizationRepository.ApprovalAuthority ctx=repository.approvalAuthority(tenantId,approvalId);
        if(ctx==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Pending approval not found: "+approvalId);
        TenantUserEntity actor=accessService.requireActiveUser(tenantId,userId);
        if(ctx.projectId()==null){
            if(ctx.reviewerEmail()!=null&&!ctx.reviewerEmail().equalsIgnoreCase(actor.getEmail()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This workflow step is assigned to another reviewer");
            return;
        }

        ProjectPermission permission=switch(ctx.authorityType()){
            case "INTERNAL_REVIEW" -> ProjectPermission.DOCUMENT_REVIEW_INTERNAL;
            case "TECHNICAL_REVIEW" -> ProjectPermission.DOCUMENT_REVIEW_TECHNICAL;
            case "CLIENT_APPROVAL" -> ProjectPermission.DOCUMENT_APPROVE_CLIENT;
            case "COMMERCIAL_CERTIFICATION" -> ProjectPermission.DOCUMENT_CERTIFY_COMMERCIAL;
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT,"Unsupported workflow authority: "+ctx.authorityType());
        };
        ProjectAuthorizationService.Decision decision=projectAuthorization.require(tenantId,userId,ctx.projectId(),permission);
        if(accessService.isTenantAdministrator(decision.actor())) return;

        if("USER".equals(ctx.assignmentType()) && ctx.reviewerEmail()!=null
                && !ctx.reviewerEmail().equalsIgnoreCase(decision.actor().getEmail()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This workflow step is assigned to another reviewer");
        if("ORGANIZATION".equals(ctx.assignmentType()) && ctx.assignmentOrganizationId()!=null
                && !ctx.assignmentOrganizationId().equals(decision.organizationId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This workflow step is assigned to another organization");
        if("PARTY_ROLE".equals(ctx.assignmentType()) && ctx.assignmentPartyRole()!=null){
            PartyRole required;
            try { required=PartyRole.valueOf(ctx.assignmentPartyRole()); }
            catch(IllegalArgumentException ex){ throw new ResponseStatusException(HttpStatus.CONFLICT,"Workflow has invalid party role assignment"); }
            if(!decision.partyRoles().contains(required))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This workflow step is assigned to "+required);
        }
        if("INTERNAL_REVIEW".equals(ctx.authorityType()) && ctx.originatorOrganizationId()!=null
                && !ctx.originatorOrganizationId().equals(decision.organizationId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Internal review belongs to the originating organization");
    }

    @Transactional(readOnly = true)
    public boolean canView(UUID tenantId, UUID userId, UUID documentId) {
        try { requireView(tenantId,userId,documentId); return true; }
        catch(ResponseStatusException ex){ if(ex.getStatusCode().value()==403||ex.getStatusCode().value()==404)return false; throw ex; }
    }

    private void evaluate(UUID tenantId, UUID userId, UUID documentId, String grantPermission, boolean mutation) {
        DocumentAuthorizationRepository.DocumentSecurity security=repository.security(tenantId,documentId);
        if(security==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Document not found: "+documentId);
        TenantUserEntity actor=accessService.requireActiveUser(tenantId,userId);
        if(security.projectId()==null){
            if(!mutation) return;
            if(accessService.isTenantAdministrator(actor)) return;
            boolean manager=actor.getRole()==UserRole.ADMIN||actor.getRole()==UserRole.MANAGER;
            if(manager||repository.hasGrant(tenantId,documentId,actor.getId(),actor.getOrganizationId(),actor.getRole().name(),grantPermission))return;
            throw denied(documentId);
        }
        ProjectPermission projectPermission=mutation
                ?(ISSUE.equals(grantPermission)?ProjectPermission.DOCUMENT_ISSUE:ProjectPermission.DOCUMENT_EDIT)
                :ProjectPermission.DOCUMENT_VIEW;
        projectAuthorization.require(tenantId,userId,security.projectId(),projectPermission);
        if(accessService.isTenantAdministrator(actor)) return;
        boolean owner=actor.getOrganizationId()!=null&&actor.getOrganizationId().equals(security.originatorOrganizationId());
        boolean explicit=repository.hasGrant(tenantId,documentId,actor.getId(),actor.getOrganizationId(),actor.getRole().name(),grantPermission);
        boolean assigned=!mutation&&repository.assignedToApproval(tenantId,documentId,actor.getEmail());
        String classification=security.classification()==null?"PROJECT":security.classification();
        boolean allowed=switch(classification){
            case "PROJECT" -> !mutation||owner||explicit;
            case "ORGANIZATION" -> owner||explicit||assigned;
            case "RESTRICTED" -> explicit||assigned;
            default -> false;
        };
        if(!allowed) throw denied(documentId);
    }

    private static ResponseStatusException denied(UUID documentId){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Document access denied by project/company/security policy: "+documentId);}
}
