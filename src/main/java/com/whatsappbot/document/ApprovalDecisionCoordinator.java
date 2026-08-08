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
    private final DocumentAuthorizationService documentAuthorization;
    private final ProjectAuthorizationService projectAuthorization;
    private final ProjectAccessService projectAccess;
    private final DocumentAuditService audit;

    @Transactional
    public void decide(UUID tenantId,UUID userId,UUID approvalId,String decision,String comments,ReviewOutcome reviewOutcome){
        var state=parallelRepository.lock(tenantId,approvalId);
        if(state==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Approval not found: "+approvalId);
        if(!"PENDING".equalsIgnoreCase(state.status())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Approval is already "+state.status());

        String normalized=reviewOutcome!=null?(reviewOutcome.isResubmissionRequired()?"REJECTED":"APPROVED"):
                (decision==null?"":decision.trim().toUpperCase());
        if(!"APPROVED".equals(normalized)&&!"REJECTED".equals(normalized))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Decision must be APPROVED or REJECTED");

        String group=parallelRepository.currentParallelGroup(approvalId,state.currentStep());
        if(group==null||group.isBlank()){
            decideSequential(tenantId,userId,approvalId,comments,reviewOutcome,normalized,state);
            return;
        }
        decideParallel(tenantId,userId,approvalId,comments,reviewOutcome,normalized,state,group);
    }

    /**
     * Sequential decisions deliberately use the same contractual authorization service that the
     * controller trusts, then perform only state transition here. The previous flow authorized the
     * request correctly and immediately called DocumentService.decideStep(), whose legacy
     * assertMayDecide() rejected ORGANIZATION/PARTY_ROLE reviewers unless they were ADMIN/MANAGER.
     * That created two contradictory authorization boundaries. There is now exactly one.
     */
    private void decideSequential(UUID tenantId,UUID userId,UUID approvalId,String comments,ReviewOutcome reviewOutcome,
                                  String normalized,ParallelApprovalRepository.ApprovalState state){
        TenantUserEntity actor=projectAccess.requireActiveUser(tenantId,userId);
        documentAuthorization.requireView(tenantId,userId,state.documentId());
        documentAuthorization.requireApprovalDecision(tenantId,userId,approvalId);

        ParallelApprovalRepository.StepRow target=parallelRepository.current(approvalId,state.currentStep());
        if(target==null) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Current approval step does not exist: "+state.currentStep());
        if(target.decision()!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This approval step has already been decided");

        if(parallelRepository.decide(target.id(),userId,normalized,comments)==0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"This approval step has already been decided");
        if(reviewOutcome!=null) parallelRepository.reviewOutcome(tenantId,state.documentId(),reviewOutcome.name());

        boolean complete;
        if("REJECTED".equals(normalized)){
            parallelRepository.finish(tenantId,approvalId,"REJECTED");
            parallelRepository.documentStatus(tenantId,state.documentId(),"REJECTED");
            complete=true;
        }else{
            Integer next=parallelRepository.nextStep(approvalId,target.stepIndex());
            if(next==null){
                parallelRepository.finish(tenantId,approvalId,"APPROVED");
                parallelRepository.documentStatus(tenantId,state.documentId(),"APPROVED");
                complete=true;
            }else{
                parallelRepository.advance(tenantId,approvalId,next);
                complete=false;
            }
        }
        recordAudit(tenantId,userId,approvalId,state.documentId(),target,null,normalized,reviewOutcome,complete,actor);
    }

    private void decideParallel(UUID tenantId,UUID userId,UUID approvalId,String comments,ReviewOutcome reviewOutcome,
                                String normalized,ParallelApprovalRepository.ApprovalState state,String group){
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
            ProjectPermission permission=ApprovalAuthority.of(target.authorityType()).permission();
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
        recordAudit(tenantId,userId,approvalId,state.documentId(),target,group,normalized,reviewOutcome,complete,actor);
    }

    private void recordAudit(UUID tenantId,UUID userId,UUID approvalId,UUID documentId,
                             ParallelApprovalRepository.StepRow target,String group,String normalized,
                             ReviewOutcome reviewOutcome,boolean complete,TenantUserEntity actor){
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("approvalId",approvalId.toString());
        payload.put("stepIndex",target.stepIndex());
        payload.put("stepName",target.stepName());
        payload.put("parallelGroup",group);
        payload.put("authority",target.authorityType());
        payload.put("decision",normalized);
        payload.put("reviewOutcome",reviewOutcome==null?null:reviewOutcome.name());
        payload.put("approvalComplete",complete);
        payload.put("decidedByEmail",actor.getEmail());
        audit.record(tenantId,documentId,userId,
                "REJECTED".equals(normalized)?DocumentAuditService.APPROVAL_REJECTED:DocumentAuditService.APPROVAL_APPROVED,payload);
    }

    private static boolean matches(TenantUserEntity actor,List<PartyRole> roles,ParallelApprovalRepository.StepRow s){
        return switch(s.assignmentType()){
            case "USER" -> s.reviewerEmail()!=null&&s.reviewerEmail().equalsIgnoreCase(actor.getEmail());
            case "ORGANIZATION" -> s.assignmentOrganizationId()!=null&&s.assignmentOrganizationId().equals(actor.getOrganizationId());
            case "PARTY_ROLE" -> s.assignmentPartyRole()!=null&&roles.stream().anyMatch(r->r.name().equals(s.assignmentPartyRole()));
            default -> false;
        };
    }
}
