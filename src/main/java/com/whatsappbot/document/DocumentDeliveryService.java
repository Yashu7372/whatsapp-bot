package com.whatsappbot.document;

import com.whatsappbot.project.ProjectAccessService;
import com.whatsappbot.project.ProjectAuthorizationService;
import com.whatsappbot.project.ProjectPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentDeliveryService {
    private static final Set<String> PURPOSES=Set.of("FOR_REVIEW","FOR_APPROVAL","FOR_INFORMATION","FOR_CONSTRUCTION","AS_BUILT");

    private final DocumentDeliveryRepository repository;
    private final DocumentAuthorizationService documentAuthorization;
    private final ProjectAuthorizationService projectAuthorization;
    private final ProjectAccessService accessService;

    @Transactional
    public IssuedRevision issueCurrentRevision(UUID tenantId,UUID userId,UUID documentId,String purpose){
        String normalized=normalizePurpose(purpose);
        documentAuthorization.requireIssue(tenantId,userId,documentId);
        DocumentDeliveryRepository.RevisionRef revision=repository.currentRevision(tenantId,documentId);
        if(revision==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Current document revision not found");
        if("ISSUED".equals(revision.issueStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Current revision is already issued");
        repository.issueCurrentRevision(tenantId,documentId,revision.versionId(),userId,normalized);
        return new IssuedRevision(documentId,revision.versionId(),revision.revisionCode(),normalized);
    }

    @Transactional(readOnly=true)
    public List<DocumentDeliveryRepository.IssuedRevisionView> issuedRevisions(UUID tenantId,UUID userId,UUID projectId){
        ProjectAuthorizationService.Decision d=projectAuthorization.require(tenantId,userId,projectId,ProjectPermission.TRANSMITTAL_CREATE);
        UUID scope=accessService.isTenantAdministrator(d.actor())?null:d.organizationId();
        return repository.issuedRevisions(tenantId,projectId,scope);
    }

    @Transactional
    public UUID createTransmittal(UUID tenantId,UUID userId,UUID projectId,CreateTransmittalRequest req){
        ProjectAuthorizationService.Decision decision=projectAuthorization.require(tenantId,userId,projectId,ProjectPermission.TRANSMITTAL_CREATE);
        UUID sender=decision.organizationId();
        if(accessService.isTenantAdministrator(decision.actor())&&req.senderOrganizationId()!=null)sender=req.senderOrganizationId();
        if(sender==null||!repository.activeProjectOrganization(tenantId,projectId,sender))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Sender organization is not active on this project");
        if(req.transmittalNo()==null||req.transmittalNo().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"transmittalNo is required");
        UUID id=UUID.randomUUID();
        repository.createTransmittal(id,tenantId,projectId,sender,req.transmittalNo().trim(),normalizePurpose(req.purpose()),req.subject(),req.message(),userId);
        return id;
    }

    @Transactional
    public void addItem(UUID tenantId,UUID userId,UUID transmittalId,UUID documentId,UUID versionId){
        DocumentDeliveryRepository.TransmittalOwner tx=requireEditableTransmittal(tenantId,userId,transmittalId,ProjectPermission.TRANSMITTAL_CREATE);
        documentAuthorization.requireView(tenantId,userId,documentId);
        if(!repository.revisionBelongsToProject(tenantId,tx.projectId(),documentId,versionId))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Transmittals may contain only issued revisions from the same project");
        repository.addItem(tenantId,transmittalId,documentId,versionId);
    }

    @Transactional
    public void addRecipient(UUID tenantId,UUID userId,UUID transmittalId,UUID recipientOrganizationId){
        DocumentDeliveryRepository.TransmittalOwner tx=requireEditableTransmittal(tenantId,userId,transmittalId,ProjectPermission.TRANSMITTAL_CREATE);
        if(recipientOrganizationId==null||recipientOrganizationId.equals(tx.senderOrganizationId()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Recipient must be a different project organization");
        if(!repository.activeProjectOrganization(tenantId,tx.projectId(),recipientOrganizationId))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Recipient organization is not active on this project");
        repository.addRecipient(tenantId,transmittalId,recipientOrganizationId);
    }

    @Transactional
    public void issueTransmittal(UUID tenantId,UUID userId,UUID transmittalId){
        requireEditableTransmittal(tenantId,userId,transmittalId,ProjectPermission.TRANSMITTAL_ISSUE);
        if(repository.itemCount(transmittalId)==0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cannot issue an empty transmittal");
        if(repository.recipientCount(transmittalId)==0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cannot issue a transmittal without recipients");
        repository.issueTransmittal(tenantId,transmittalId,userId);
    }

    @Transactional
    public void acknowledge(UUID tenantId,UUID userId,UUID transmittalId){
        DocumentDeliveryRepository.TransmittalOwner tx=repository.transmittalOwner(tenantId,transmittalId);
        if(tx==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Transmittal not found");
        ProjectAuthorizationService.Decision d=projectAuthorization.require(tenantId,userId,tx.projectId(),ProjectPermission.TRANSMITTAL_ACKNOWLEDGE);
        if(d.organizationId()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tenant administrators must acknowledge through a recipient organization user");
        if(repository.acknowledge(tenantId,transmittalId,d.organizationId(),userId)==0)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Your organization is not an unacknowledged recipient of this transmittal");
    }

    @Transactional(readOnly=true)
    public List<DocumentDeliveryRepository.TransmittalView> list(UUID tenantId,UUID userId,UUID projectId){
        ProjectAuthorizationService.Decision d=projectAuthorization.require(tenantId,userId,projectId,ProjectPermission.TRANSMITTAL_VIEW);
        UUID scope=accessService.isTenantAdministrator(d.actor())?null:d.organizationId();
        return repository.list(tenantId,projectId,scope);
    }

    private DocumentDeliveryRepository.TransmittalOwner requireEditableTransmittal(UUID tenantId,UUID userId,UUID transmittalId,ProjectPermission permission){
        DocumentDeliveryRepository.TransmittalOwner tx=repository.transmittalOwner(tenantId,transmittalId);
        if(tx==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Transmittal not found");
        if(!"DRAFT".equals(tx.status()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only DRAFT transmittals can be changed or issued");
        ProjectAuthorizationService.Decision d=projectAuthorization.require(tenantId,userId,tx.projectId(),permission);
        if(!accessService.isTenantAdministrator(d.actor())&&!tx.senderOrganizationId().equals(d.organizationId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only the sender organization can change this transmittal");
        return tx;
    }

    private static String normalizePurpose(String purpose){String p=purpose==null?"FOR_INFORMATION":purpose.trim().toUpperCase();if(!PURPOSES.contains(p))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported issue purpose: "+p);return p;}
    public record IssuedRevision(UUID documentId,UUID versionId,String revisionCode,String purpose){}
    public record CreateTransmittalRequest(String transmittalNo,UUID senderOrganizationId,String purpose,String subject,String message){}
}
