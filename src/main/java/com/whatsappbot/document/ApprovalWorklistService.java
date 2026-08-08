package com.whatsappbot.document;

import com.whatsappbot.project.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApprovalWorklistService {
    private final ApprovalWorklistRepository repository;
    private final DocumentAuthorizationService authorization;
    private final ProjectAccessService accessService;

    @Transactional(readOnly=true)
    public List<Item> mine(UUID tenantId,UUID userId){
        var actor=accessService.requireActiveUser(tenantId,userId);
        // Narrowed by assignment in SQL first; the contractual authority check still decides.
        return repository.pendingFor(tenantId,userId,actor.getEmail(),actor.getOrganizationId()).stream()
                .filter(r->allowed(tenantId,userId,r.approvalId())).map(this::toItem).toList();
    }

    @Transactional
    public int refreshEscalations(UUID tenantId,UUID userId){
        var actor=accessService.requireActiveUser(tenantId,userId);
        if(!accessService.isTenantAdministrator(actor))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only a true tenant administrator can refresh workflow escalations");
        int count=0;
        for(var row:repository.overdue(tenantId)){
            repository.enqueueEscalation(tenantId,row);
            repository.markEscalated(row.stepId());
            count++;
        }
        return count;
    }

    private boolean allowed(UUID tenantId,UUID userId,UUID approvalId){
        try {authorization.requireApprovalDecision(tenantId,userId,approvalId);return true;}
        catch(ResponseStatusException ex){if(ex.getStatusCode().value()==403||ex.getStatusCode().value()==404)return false;throw ex;}
    }

    private Item toItem(ApprovalWorklistRepository.Row r){
        LocalDateTime now=LocalDateTime.now();
        String sla="NO_SLA";
        Long hours=null;
        if(r.dueAt()!=null){
            hours=Duration.between(now,r.dueAt()).toHours();
            if(r.dueAt().isBefore(now)) sla="OVERDUE";
            else if(r.dueAt().isBefore(now.plusHours(24))) sla="DUE_SOON";
            else sla="ON_TRACK";
        }
        return new Item(r.stepId(),r.approvalId(),r.documentId(),r.projectId(),r.documentCode(),r.title(),
                r.stepIndex(),r.stepName(),r.authorityType(),r.assignmentType(),r.required(),r.parallelGroup(),
                r.dueAt(),r.escalatedAt(),sla,hours);
    }

    public record Item(UUID stepId,UUID approvalId,UUID documentId,UUID projectId,String documentCode,String title,
                       int stepIndex,String stepName,String authorityType,String assignmentType,boolean required,
                       String parallelGroup,LocalDateTime dueAt,LocalDateTime escalatedAt,String slaStatus,Long hoursRemaining){}
}
