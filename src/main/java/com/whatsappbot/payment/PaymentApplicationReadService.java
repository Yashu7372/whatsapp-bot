package com.whatsappbot.payment;

import com.whatsappbot.project.ProjectAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentApplicationReadService {
    private final PaymentApplicationRepository repository;
    private final PaymentApplicationService paymentService;
    private final PaymentAuthorizationService authorization;

    @Transactional(readOnly = true)
    public List<PaymentApplicationService.ApplicationView> list(UUID tenantId,UUID userId,UUID projectId){
        ProjectAuthorizationService.Decision decision=authorization.requireList(tenantId,userId,projectId);
        var applications=decision.scope()==ProjectAuthorizationService.DataScope.ORGANIZATION
                ? repository.findAllByTenantIdAndProjectIdAndClaimedByOrgIdOrderByCreatedAtDesc(tenantId,projectId,decision.organizationId())
                : repository.findAllByTenantIdAndProjectIdOrderByCreatedAtDesc(tenantId,projectId);
        return applications.stream().map(paymentService::toView).toList();
    }
}
