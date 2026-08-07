package com.whatsappbot.payment;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.TenantUserRepository;
import com.whatsappbot.auth.UserRole;
import com.whatsappbot.document.DocumentEntity;
import com.whatsappbot.document.DocumentRepository;
import com.whatsappbot.document.DocumentStatus;
import com.whatsappbot.document.ReviewOutcome;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.project.OrganizationEntity;
import com.whatsappbot.project.OrganizationRepository;
import com.whatsappbot.project.ProjectEntity;
import com.whatsappbot.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Covers the commercial rules of a payment claim.
 *
 * <p>The two that carry the module: money cannot be claimed against work that has not been
 * approved, and a company cannot certify its own claim. Both are the checks an email-and-
 * spreadsheet process cannot make, so they are what a certified figure here rests on.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentApplicationServiceTest {

    @Mock private PaymentApplicationRepository applicationRepository;
    @Mock private PaymentApplicationItemRepository itemRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private TenantUserRepository userRepository;
    @Mock private ProjectService projectService;

    @InjectMocks private PaymentApplicationService service;

    private UUID tenantId;
    private UUID projectId;
    private UUID applicationId;
    private TenantEntity tenant;
    private OrganizationEntity contractor;
    private PaymentApplicationEntity application;
    private DocumentEntity approvedDocument;
    private final List<PaymentApplicationItemEntity> items = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        applicationId = UUID.randomUUID();

        tenant = new TenantEntity();
        tenant.setId(tenantId);

        contractor = new OrganizationEntity();
        contractor.setId(UUID.randomUUID());
        contractor.setName("Acme Contracting");
        contractor.setTenant(tenant);

        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTenant(tenant);
        project.setCurrency("AED");
        project.setRetentionPercent(new BigDecimal("10.00"));

        application = new PaymentApplicationEntity();
        application.setId(applicationId);
        application.setTenant(tenant);
        application.setProjectId(projectId);
        application.setClaimedByOrg(contractor);
        application.setRetentionPercent(new BigDecimal("10.00"));
        application.setCurrency("AED");

        approvedDocument = new DocumentEntity();
        approvedDocument.setId(UUID.randomUUID());
        approvedDocument.setTenant(tenant);
        approvedDocument.setProjectId(projectId);
        approvedDocument.setTitle("Structural works — block A");
        approvedDocument.setStatus(DocumentStatus.APPROVED);
        approvedDocument.setReviewOutcome(ReviewOutcome.CODE_A);

        when(projectService.get(tenantId, projectId)).thenReturn(project);
        when(projectService.isParticipant(any(), any(), any())).thenReturn(true);
        when(applicationRepository.findByIdAndTenantId(applicationId, tenantId))
                .thenReturn(Optional.of(application));
        when(applicationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(applicationRepository.sumCertifiedToDate(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(organizationRepository.findByIdAndTenantId(contractor.getId(), tenantId))
                .thenReturn(Optional.of(contractor));
        when(documentRepository.findByIdAndTenantId(approvedDocument.getId(), tenantId))
                .thenReturn(Optional.of(approvedDocument));
        when(itemRepository.save(any())).thenAnswer(inv -> {
            PaymentApplicationItemEntity i = inv.getArgument(0);
            if (i.getId() == null) i.setId(UUID.randomUUID());
            items.add(i);
            return i;
        });
        when(itemRepository.findAllByTenantIdAndPaymentApplicationIdOrderByCreatedAtAsc(tenantId, applicationId))
                .thenReturn(items);
    }

    private TenantUserEntity user(UUID organizationId) {
        TenantUserEntity u = new TenantUserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail("user@example.com");
        u.setRole(UserRole.MANAGER);
        u.setTenant(tenant);
        u.setActive(true);
        u.setOrganizationId(organizationId);
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void retentionIsTakenOffTheGrossAtTheProjectRate() {
        service.addItem(tenantId, applicationId, new PaymentApplicationService.AddItemRequest(
                approvedDocument.getId(), "Block A", new BigDecimal("100000.00")));

        assertThat(application.getGrossClaimed()).isEqualByComparingTo("100000.00");
        assertThat(application.getRetentionAmount()).isEqualByComparingTo("10000.00");
        assertThat(application.getNetCertified()).isEqualByComparingTo("90000.00");
    }

    @Test
    void retentionRoundsToTwoDecimalPlaces() {
        application.setRetentionPercent(new BigDecimal("7.50"));

        service.addItem(tenantId, applicationId, new PaymentApplicationService.AddItemRequest(
                approvedDocument.getId(), "Odd amount", new BigDecimal("1333.33")));

        // 1333.33 * 7.5% = 99.99975 -> 100.00
        assertThat(application.getRetentionAmount()).isEqualByComparingTo("100.00");
        assertThat(application.getNetCertified()).isEqualByComparingTo("1233.33");
    }

    @Test
    void workThatIsNotApprovedCannotBeClaimed() {
        approvedDocument.setStatus(DocumentStatus.IN_REVIEW);

        assertThatThrownBy(() -> service.addItem(tenantId, applicationId,
                new PaymentApplicationService.AddItemRequest(
                        approvedDocument.getId(), null, new BigDecimal("500.00"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("only an approved document can be claimed");
    }

    @Test
    void aDocumentReturnedForResubmissionCannotBeClaimed() {
        approvedDocument.setReviewOutcome(ReviewOutcome.CODE_C);

        assertThatThrownBy(() -> service.addItem(tenantId, applicationId,
                new PaymentApplicationService.AddItemRequest(
                        approvedDocument.getId(), null, new BigDecimal("500.00"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("needs resubmission");
    }

    @Test
    void approvedWithCommentsIsStillClaimable() {
        approvedDocument.setReviewOutcome(ReviewOutcome.CODE_B);

        assertThat(service.addItem(tenantId, applicationId,
                new PaymentApplicationService.AddItemRequest(
                        approvedDocument.getId(), null, new BigDecimal("500.00")))).isNotNull();
    }

    @Test
    void aDocumentFromAnotherProjectCannotBeClaimed() {
        approvedDocument.setProjectId(UUID.randomUUID());

        assertThatThrownBy(() -> service.addItem(tenantId, applicationId,
                new PaymentApplicationService.AddItemRequest(
                        approvedDocument.getId(), null, new BigDecimal("500.00"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("different project");
    }

    @Test
    void aClaimCannotBeCertifiedByTheCompanyThatRaisedIt() {
        application.setStatus(PaymentApplicationStatus.SUBMITTED);
        TenantUserEntity claimantStaff = user(contractor.getId());

        assertThatThrownBy(() -> service.decide(tenantId, claimantStaff.getId(), applicationId, true, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot be certified by the company that raised it");
    }

    @Test
    void anotherPartyCanCertify() {
        application.setStatus(PaymentApplicationStatus.SUBMITTED);
        TenantUserEntity consultantStaff = user(UUID.randomUUID());

        var view = service.decide(tenantId, consultantStaff.getId(), applicationId, true, "Measured on site");

        assertThat(view.status()).isEqualTo(PaymentApplicationStatus.CERTIFIED);
        assertThat(application.getCertifiedBy()).isEqualTo(consultantStaff);
    }

    @Test
    void onlyASubmittedClaimCanBeCertified() {
        application.setStatus(PaymentApplicationStatus.DRAFT);
        TenantUserEntity consultantStaff = user(UUID.randomUUID());

        assertThatThrownBy(() -> service.decide(tenantId, consultantStaff.getId(), applicationId, true, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Only a submitted claim");
    }

    @Test
    void aSubmittedClaimCanNoLongerBeEdited() {
        application.setStatus(PaymentApplicationStatus.SUBMITTED);

        assertThatThrownBy(() -> service.addItem(tenantId, applicationId,
                new PaymentApplicationService.AddItemRequest(
                        approvedDocument.getId(), null, new BigDecimal("100.00"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("can no longer be changed");
    }

    @Test
    void anEmptyClaimCannotBeSubmitted() {
        assertThatThrownBy(() -> service.submit(tenantId, applicationId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no items");
    }

    @Test
    void aCompanyNotOnTheProjectCannotRaiseAClaim() {
        when(projectService.isParticipant(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.create(tenantId, UUID.randomUUID(),
                new PaymentApplicationService.CreateRequest(
                        projectId, "IPC-01", contractor.getId(), null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not a participant on this project");
    }
}
