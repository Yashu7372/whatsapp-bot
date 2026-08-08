package com.whatsappbot.payment;

import com.whatsappbot.project.ProjectAuthorizationService;
import com.whatsappbot.project.ProjectPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentAuthorizationService {
    private final PaymentApplicationRepository repository;
    private final ProjectAuthorizationService projectAuthorization;

    @Transactional(readOnly = true)
    public ProjectAuthorizationService.Decision requireList(UUID tenantId,UUID userId,UUID projectId){
        try{
            return projectAuthorization.require(tenantId,userId,projectId,ProjectPermission.PAYMENT_VIEW_PROJECT);
        }catch(ResponseStatusException ex){
            if(ex.getStatusCode().value()!=403) throw ex;
            return projectAuthorization.require(tenantId,userId,projectId,ProjectPermission.PAYMENT_VIEW_ORGANIZATION);
        }
    }

    @Transactional(readOnly = true)
    public PaymentApplicationEntity requireView(UUID tenantId,UUID userId,UUID applicationId){
        PaymentApplicationEntity app=application(tenantId,applicationId);
        ProjectAuthorizationService.Decision d=requireList(tenantId,userId,app.getProjectId());
        if(d.scope()== ProjectAuthorizationService.DataScope.ORGANIZATION
                && !app.getClaimedByOrg().getId().equals(d.organizationId())){
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Payment application belongs to another project organization");
        }
        return app;
    }

    @Transactional(readOnly = true)
    public void requireCreate(UUID tenantId,UUID userId,UUID projectId){
        projectAuthorization.require(tenantId,userId,projectId,ProjectPermission.PAYMENT_CREATE_ORGANIZATION);
    }

    @Transactional(readOnly = true)
    public void requireCertify(UUID tenantId,UUID userId,UUID applicationId){
        PaymentApplicationEntity app=application(tenantId,applicationId);
        projectAuthorization.require(tenantId,userId,app.getProjectId(),ProjectPermission.PAYMENT_CERTIFY);
    }

    @Transactional(readOnly = true)
    public void requireMarkPaid(UUID tenantId,UUID userId,UUID applicationId){
        PaymentApplicationEntity app=application(tenantId,applicationId);
        projectAuthorization.require(tenantId,userId,app.getProjectId(),ProjectPermission.PAYMENT_MARK_PAID);
    }

    private PaymentApplicationEntity application(UUID tenantId,UUID id){
        return repository.findByIdAndTenantId(id,tenantId)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment application not found: "+id));
    }
}
