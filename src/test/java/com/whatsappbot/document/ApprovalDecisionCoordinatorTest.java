package com.whatsappbot.document;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.UserRole;
import com.whatsappbot.project.ProjectAccessService;
import com.whatsappbot.project.ProjectAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.*;

/** Regression coverage for the duplicate sequential authorization bug. */
@ExtendWith(MockitoExtension.class)
class ApprovalDecisionCoordinatorTest {

    @Mock ParallelApprovalRepository repository;
    @Mock DocumentAuthorizationService documentAuthorization;
    @Mock ProjectAuthorizationService projectAuthorization;
    @Mock ProjectAccessService projectAccess;
    @Mock DocumentAuditService audit;

    ApprovalDecisionCoordinator coordinator;

    final UUID tenantId=UUID.randomUUID();
    final UUID userId=UUID.randomUUID();
    final UUID approvalId=UUID.randomUUID();
    final UUID documentId=UUID.randomUUID();
    final UUID projectId=UUID.randomUUID();
    final UUID organizationId=UUID.randomUUID();
    final UUID stepId=UUID.randomUUID();

    @BeforeEach
    void setUp(){
        coordinator=new ApprovalDecisionCoordinator(repository,documentAuthorization,projectAuthorization,projectAccess,audit);
    }

    @Test
    @DisplayName("sequential party-role reviewer uses the contractual authorization service exactly once")
    void sequentialPartyRoleReviewerCanDecideWithoutLegacyManagerGate(){
        TenantUserEntity reviewer=new TenantUserEntity();
        reviewer.setId(userId);
        reviewer.setEmail("consultant.reviewer@example.test");
        reviewer.setRole(UserRole.REVIEWER);
        reviewer.setOrganizationId(organizationId);
        reviewer.setActive(true);

        when(projectAccess.requireActiveUser(tenantId,userId)).thenReturn(reviewer);
        when(repository.lock(tenantId,approvalId)).thenReturn(
                new ParallelApprovalRepository.ApprovalState(documentId,1,"PENDING",projectId,UUID.randomUUID()));
        when(repository.currentParallelGroup(approvalId,1)).thenReturn(null);
        when(repository.current(approvalId,1)).thenReturn(
                new ParallelApprovalRepository.StepRow(stepId,1,"Consultant Review","TECHNICAL_REVIEW",
                        "PARTY_ROLE",null,"CONSULTANT",null,true,null));
        when(repository.decide(stepId,userId,"APPROVED","Reviewed")).thenReturn(1);
        when(repository.nextStep(approvalId,1)).thenReturn(2);

        coordinator.decide(tenantId,userId,approvalId,"APPROVED","Reviewed",null);

        verify(documentAuthorization).requireView(tenantId,userId,documentId);
        verify(documentAuthorization).requireApprovalDecision(tenantId,userId,approvalId);
        verify(repository).decide(stepId,userId,"APPROVED","Reviewed");
        verify(repository).advance(tenantId,approvalId,2);
        verify(repository,never()).finish(any(),any(),eq("REJECTED"));
        // Project authorization is performed inside DocumentAuthorizationService for a sequential
        // stage; the coordinator does not reinterpret the user's role a second time.
        verifyNoInteractions(projectAuthorization);
    }
}
