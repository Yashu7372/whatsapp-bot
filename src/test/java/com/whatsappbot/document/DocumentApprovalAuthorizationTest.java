package com.whatsappbot.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.TenantUserRepository;
import com.whatsappbot.auth.UserRole;
import com.whatsappbot.domain.tenant.TenantEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers who may decide an approval step.
 *
 * <p>Before these checks existed the only guard was that the approval belonged to the caller's
 * tenant, so any authenticated user could approve any step of any document and be recorded as
 * the approver. In a flow where approval releases payment that is the failure that matters most,
 * so each way of getting past the guard is pinned down here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentApprovalAuthorizationTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentVersionRepository versionRepository;
    @Mock private DocumentControlWorkflowRepository workflowRepository;
    @Mock private DocumentApprovalRepository approvalRepository;
    @Mock private DocumentApprovalStepRepository stepRepository;
    @Mock private DocumentCommentRepository commentRepository;
    @Mock private com.whatsappbot.storage.MediaAssetRepository mediaAssetRepository;
    @Mock private DocumentEncryptionMetadataRepository encryptionMetadataRepository;
    @Mock private com.whatsappbot.storage.StorageService storageService;
    @Mock private com.whatsappbot.domain.tenant.TenantRepository tenantRepository;
    @Mock private TenantUserRepository userRepository;
    @Mock private DocumentAuditService auditService;
    @Mock private ObjectMapper objectMapper;
    @Mock private com.whatsappbot.project.ProjectService projectService;
    @Mock private com.whatsappbot.project.ProjectAccessService accessService;
    @Mock private com.whatsappbot.project.DocumentNumberService numberService;
    @Mock private com.whatsappbot.project.OrganizationRepository organizationRepository;

    @InjectMocks private DocumentService service;

    private static final String ASSIGNED_REVIEWER_EMAIL = "engineer@consultant.example";

    private TenantEntity tenant;
    private UUID tenantId;
    private UUID approvalId;
    private UUID documentId;
    private DocumentApprovalEntity approval;
    private DocumentApprovalStepEntity step;
    private DocumentEntity document;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = new TenantEntity();
        tenant.setId(tenantId);

        documentId = UUID.randomUUID();
        approvalId = UUID.randomUUID();

        document = new DocumentEntity();
        document.setId(documentId);
        document.setTenant(tenant);
        document.setStatus(DocumentStatus.IN_REVIEW);

        approval = new DocumentApprovalEntity();
        approval.setId(approvalId);
        approval.setTenant(tenant);
        approval.setDocumentId(documentId);
        approval.setCurrentStep(0);
        approval.setStatus("PENDING");

        step = new DocumentApprovalStepEntity();
        step.setApprovalId(approvalId);
        step.setStepIndex(0);
        step.setStepName("Consultant review");
        step.setReviewerEmail(ASSIGNED_REVIEWER_EMAIL);

        when(approvalRepository.lockByIdAndTenantId(approvalId, tenantId)).thenReturn(Optional.of(approval));
        when(stepRepository.findAllByApprovalIdOrderByStepIndex(approvalId)).thenReturn(List.of(step));
        when(documentRepository.findByIdAndTenantId(documentId, tenantId)).thenReturn(Optional.of(document));
        when(stepRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(approvalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private TenantUserEntity user(String email, UserRole role, TenantEntity owner, boolean active) {
        TenantUserEntity u = new TenantUserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setRole(role);
        u.setTenant(owner);
        u.setActive(active);
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void assignedReviewerCanApprove() {
        TenantUserEntity reviewer = user(ASSIGNED_REVIEWER_EMAIL, UserRole.REVIEWER, tenant, true);

        DocumentApprovalStepEntity decided =
                service.decideStep(tenantId, reviewer.getId(), approvalId, "APPROVED", "Looks correct");

        assertThat(decided.getDecision()).isEqualTo("APPROVED");
        assertThat(decided.getReviewer()).isEqualTo(reviewer);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.APPROVED);
    }

    @Test
    void reviewerEmailMatchIsCaseInsensitive() {
        TenantUserEntity reviewer = user(ASSIGNED_REVIEWER_EMAIL.toUpperCase(), UserRole.REVIEWER, tenant, true);

        assertThat(service.decideStep(tenantId, reviewer.getId(), approvalId, "APPROVED", null))
                .isNotNull();
    }

    @Test
    void anotherUserCannotDecideAStepAssignedToSomeoneElse() {
        TenantUserEntity intruder = user("someone.else@contractor.example", UserRole.MANAGER, tenant, true);

        assertThatThrownBy(() ->
                service.decideStep(tenantId, intruder.getId(), approvalId, "APPROVED", "rubber stamp"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("assigned to another reviewer");

        verify(stepRepository, never()).save(any());
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.IN_REVIEW);
    }

    @Test
    void readOnlyUserCanNeverDecide() {
        TenantUserEntity viewer = user(ASSIGNED_REVIEWER_EMAIL, UserRole.VIEWER, tenant, true);

        assertThatThrownBy(() -> service.decideStep(tenantId, viewer.getId(), approvalId, "APPROVED", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Read-only users");
    }

    @Test
    void unassignedStepFallsBackToAdminOrManager() {
        step.setReviewerEmail(null);

        TenantUserEntity reviewer = user("reviewer@contractor.example", UserRole.REVIEWER, tenant, true);
        assertThatThrownBy(() -> service.decideStep(tenantId, reviewer.getId(), approvalId, "APPROVED", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("administrator or manager");

        TenantUserEntity manager = user("manager@contractor.example", UserRole.MANAGER, tenant, true);
        assertThat(service.decideStep(tenantId, manager.getId(), approvalId, "APPROVED", null)).isNotNull();
    }

    @Test
    void userFromAnotherTenantIsRejected() {
        TenantEntity otherTenant = new TenantEntity();
        otherTenant.setId(UUID.randomUUID());
        TenantUserEntity foreigner = user(ASSIGNED_REVIEWER_EMAIL, UserRole.ADMIN, otherTenant, true);

        assertThatThrownBy(() -> service.decideStep(tenantId, foreigner.getId(), approvalId, "APPROVED", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not an active member");
    }

    @Test
    void deactivatedUserIsRejected() {
        TenantUserEntity disabled = user(ASSIGNED_REVIEWER_EMAIL, UserRole.ADMIN, tenant, false);

        assertThatThrownBy(() -> service.decideStep(tenantId, disabled.getId(), approvalId, "APPROVED", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not an active member");
    }

    @Test
    void unknownUserIdIsRejectedRatherThanRecordedAsNobody() {
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decideStep(tenantId, UUID.randomUUID(), approvalId, "APPROVED", null))
                .isInstanceOf(ResponseStatusException.class);

        verify(stepRepository, never()).save(any());
    }

    @Test
    void aCompletedApprovalCannotBeDecidedAgain() {
        approval.setStatus("REJECTED");
        TenantUserEntity reviewer = user(ASSIGNED_REVIEWER_EMAIL, UserRole.REVIEWER, tenant, true);

        assertThatThrownBy(() -> service.decideStep(tenantId, reviewer.getId(), approvalId, "APPROVED", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already REJECTED");
    }

    @Test
    void anUnrecognisedDecisionIsRejected() {
        TenantUserEntity reviewer = user(ASSIGNED_REVIEWER_EMAIL, UserRole.REVIEWER, tenant, true);

        assertThatThrownBy(() -> service.decideStep(tenantId, reviewer.getId(), approvalId, "MAYBE", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("must be APPROVED or REJECTED");

        verify(stepRepository, never()).save(any());
    }

    @Test
    void everyDecisionIsRecordedInTheAuditTrail() {
        TenantUserEntity reviewer = user(ASSIGNED_REVIEWER_EMAIL, UserRole.REVIEWER, tenant, true);

        service.decideStep(tenantId, reviewer.getId(), approvalId, "REJECTED", "Missing test certificates");

        verify(auditService).record(eq(tenantId), eq(documentId), eq(reviewer.getId()),
                eq(DocumentAuditService.APPROVAL_REJECTED), any());
    }

    @Test
    void aRefusedDecisionLeavesNoAuditEvent() {
        TenantUserEntity intruder = user("someone.else@contractor.example", UserRole.MANAGER, tenant, true);

        assertThatThrownBy(() -> service.decideStep(tenantId, intruder.getId(), approvalId, "APPROVED", null))
                .isInstanceOf(ResponseStatusException.class);

        verify(auditService, never()).record(any(), any(), any(), anyString(), any());
    }
}
