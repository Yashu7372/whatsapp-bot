package com.whatsappbot.document;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.project.PartyRole;
import com.whatsappbot.project.ProjectAccessService;
import com.whatsappbot.project.ProjectAuthorizationService;
import com.whatsappbot.project.ProjectPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApprovalDecisionCoordinator {
    private final ParallelApprovalRepository parallelRepository;
    private final DocumentService documentService;
    private final DocumentAuthorizationService documentAuthorization;
    private final ProjectAuthorizationService projectAuthorization;
    private final ProjectAccessService projectAccess;
    private final DocumentAuditService audit;

    @Transactional
    public void decide(UUID tenantId,UUID userId,UUID approvalId,String decision,String comments,ReviewOutcome reviewOutcome){
        var state=parallelRepository.lock(tenantId,approvalId);
        if(state==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Approval not found: "+approvalId);
        if(!"PENDING".equalsIgnoreCase(state.status())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Approval is already "+state.status());
        String group=parallelRepository.currentParallelGroup(approvalId,state.currentStep());
        if(group==null||group.isBlank()){
            documentAuthorization.requireApprovalDecision(tenantId,userId,approvalId);
            documentService.decideStep(tenantId,userId,approvalId,decision,comments,reviewOutcome);
            return;
        }

        String normalized=reviewOutcome!=null?(reviewOutcome.isResubmissionRequired()?"REJECTED":"APPROVED"):
                (decision==null?"":decision.trim().toUpperCase());
        if(!"APPROVED".equals(normalized)&&!"REJECTED".equals(normalized))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Decision must be APPROVED or REJECTED");

        TenantUserEntity actor=projectAccess.requireActiveUser(tenantId,userId);
        documentAuthorization.requireView(tenantId,userId,state.documentId());
        List<PartyRole> actorRoles=state.projectId()==null?List.of():projectAccess.rolesOnProject(tenantId,state.projectId(),actor);
        List<ParallelApprovalRepository.StepRow> groupSteps=parallelRepository.group(approvalId,group);
        ParallelApprovalRepository.StepRow target=groupSteps.stream()
                .filter(s->s.decision()==null)
                .filter(s->matches(actor,actorRoles,s))
                .findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN,"No pending reviewer slot in this parallel stage is assigned to you"));

        if(state.projectId()!=null){
            ProjectPermission permission=permission(target.authorityType());
            var auth=projectAuthorization.require(tenantId,userId,state.projectId(),permission);
            if("INTERNAL_REVIEW".equals(target.authorityType())&&state.originatorOrganizationId()!=null
                    &&!state.originatorOrganizationId().equals(auth.organizationId()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Internal review belongs to the originating organization");
        }

        if(parallelRepository.decide(target.id(),userId,normalized,comments)==0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"This parallel review slot has already been decided");
        if(reviewOutcome!=null) parallelRepository.reviewOutcome(tenantId,state.documentId(),reviewOutcome.name());

        boolean complete=false;
        if(target.required()&&"REJECTED".equals(normalized)){
            parallelRepository.finish(tenantId,approvalId,"REJECTED");
            parallelRepository.documentStatus(tenantId,state.documentId(),"REJECTED");
            complete=true;
        }else{
            List<ParallelApprovalRepository.StepRow> refreshed=parallelRepository.group(approvalId,group);
            boolean requiredComplete=refreshed.stream().filter(ParallelApprovalRepository.StepRow::required)
                    .allMatch(s->"APPROVED".equals(s.decision()));
            if(requiredComplete){
                parallelRepository.skipOptional(approvalId,group);
                int maxIndex=refreshed.stream().mapToInt(ParallelApprovalRepository.StepRow::stepIndex).max().orElse(state.currentStep());
                Integer next=parallelRepository.nextStep(approvalId,maxIndex);
                if(next==null){
                    parallelRepository.finish(tenantId,approvalId,"APPROVED");
                    parallelRepository.documentStatus(tenantId,state.documentId(),"APPROVED");
                    complete=true;
                }else parallelRepository.advance(tenantId,approvalId,next);
            }
        }

        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("approvalId",approvalId.toString());payload.put("stepIndex",target.stepIndex());payload.put("stepName",target.stepName());
        payload.put("parallelGroup",group);payload.put("authority",target.authorityType());payload.put("decision",normalized);
        payload.put("reviewOutcome",reviewOutcome==null?null:reviewOutcome.name());payload.put("approvalComplete",complete);payload.put("decidedByEmail",actor.getEmail());
        audit.record(tenantId,state.documentId(),userId,"REJECTED".equals(normalized)?DocumentAuditService.APPROVAL_REJECTED:DocumentAuditService.APPROVAL_APPROVED,payload);
    }

    private static boolean matches(TenantUserEntity actor,List<PartyRole> roles,ParallelApprovalRepository.StepRow s){
        return switch(s.assignmentType()){
            case "USER" -> s.reviewerEmail()!=null&&s.reviewerEmail().equalsIgnoreCase(actor.getEmail());
            case "ORGANIZATION" -> s.assignmentOrganizationId()!=null&&s.assignmentOrganizationId().equals(actor.getOrganizationId());
            case "PARTY_ROLE" -> s.assignmentPartyRole()!=null&&roles.stream().anyMatch(r->r.name().equals(s.assignmentPartyRole()));
            default -> false;
        };
    }
    private static ProjectPermission permission(String authority){return switch(authority){
        case "INTERNAL_REVIEW"->ProjectPermission.DOCUMENT_REVIEW_INTERNAL;
        case "TECHNICAL_REVIEW"->ProjectPermission.DOCUMENT_REVIEW_TECHNICAL;
        case "CLIENT_APPROVAL"->ProjectPermission.DOCUMENT_APPROVE_CLIENT;
        case "COMMERCIAL_CERTIFICATION"->ProjectPermission.DOCUMENT_CERTIFY_COMMERCIAL;
        default->throw new ResponseStatusException(HttpStatus.CONFLICT,"Unsupported workflow authority: "+authority);
    };}
}
