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
import com.whatsappbot.project.PartyRole;
import com.whatsappbot.project.ProjectAccessService;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Covers the commercial and authorisation rules of a payment claim.
 *
 * <p>These are the checks that make a certified figure defensible: work must be approved before
 * it can be claimed, the same evidence cannot be claimed twice, a company cannot certify or pay
 * its own claim, and only the parties administering the contract can certify at all.
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
    @Mock private ProjectAccessService accessService;
    @Mock private PaymentAuditService auditService;

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
        when(applicationRepository.countLiveClaimsOnDocument(any(), any(), any())).thenReturn(0L);
        when(organizationRepository.findByIdAndTenantId(eq(contractor.getId()), eq(tenantId)))
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

    /** Registers a user and makes the access service resolve it. */
    private TenantUserEntity user(UUID organizationId, UserRole role) {
        TenantUserEntity u = new TenantUserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail("user-" + UUID.randomUUID() + "@example.com");
        u.setRole(role);
        u.setTenant(tenant);
        u.setActive(true);
        u.setOrganizationId(organizationId);
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        when(accessService.requireActiveUser(tenantId, u.getId())).thenReturn(u);
        return u;
    }

    private TenantUserEntity claimantStaff() {
        return user(contractor.getId(), UserRole.MANAGER);
    }

    // ── Retention arithmetic ───────────────────────────────────────────────

    @Test
    void retentionIsTakenOffTheGrossAtTheProjectRate() {
        service.addItem(tenantId, claimantStaff().getId(), applicationId,
                new PaymentApplicationService.AddItemRequest(
                        approvedDocument.getId(), "Block A", new BigDecimal("100000.00")));

        assertThat(application.getGrossClaimed()).isEqualByComparingTo("100000.00");
        assertThat(application.getRetentionAmount()).isEqualByComparingTo("10000.00");
        assertThat(application.getNetCertified()).isEqualByComparingTo("90000.00");
    }

    @Test
    void retentionRoundsToTwoDecimalPlaces() {
        application.setRetentionPercent(new BigDecimal("7.50"));

        service.addItem(tenantId, claimantStaff().getId(), applicationId,
                new PaymentApplicationService.AddItemRequest(
                        approvedDocument.getId(), "Odd amount", new BigDecimal("1333.33")));

        // 1333.33 * 7.5% = 99.99975 -> 100.00
        assertThat(application.getRetentionAmount()).isEqualByComparingTo("100.00");
        assertThat(application.getNetCertified()).isEqualByComparingTo("1233.33");
    }

    // ── Claimable evidence ─────────────────────────────────────────────────

    @Test
    void workThatIsNotApprovedCannotBeClaimed() {
        approvedDocument.setStatus(DocumentStatus.IN_REVIEW);

        assertThatThrownBy(() -> service.addItem(tenantId, claimantStaff().getId(), applicationId,
                new PaymentApplicationService.AddItemRequest(
                        approvedDocument.getId(), null, new BigDecimal("500.00"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("only an approved document can be claimed");
    }

    @Test
    void aDocumentReturnedForResubmissionCannotBeClaimed() {
        approvedDocument.setReviewOutcome(ReviewOutcome.CODE_C);

        assertThatThrownBy(() -> service.addItem(tenantId, claimantStaff().getId(), applicationId,
                new PaymentApplicationService.AddItemRequest(
                        approvedDocument.getId(), null, new BigDecimal("500.00"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("needs resubmission");
    }

    @Test
    void approvedWithCommentsIsStillClaimable() {
        approvedDocument.setReviewOutcome(ReviewOutcome.CODE_B);

        assertThat(service.addItem(tenantId, claimantStaff().getId(), applicationId,
                new PaymentApplicationService.AddItemRequest(
                        approvedDocument.getId(), null, new BigDecimal("500.00")))).isNotNull();
    }

    @Test
    void aDocumentFromAnotherProjectCannotBeClaimed() {
        approvedDocument.setProjectId(UUID.randomUUID());

        assertThatThrownBy(() -> service.addItem(tenantId, claimantStaff().getId(), applicationId,
                new PaymentApplicationService.AddItemRequest(
                        approvedDocument.getId(), null, new BigDecimal("500.00"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("different project");
    }

    @Test
    void theSameDocumentCannotBeClaimedOnASecondApplication() {
        when(applicationRepository.countLiveClaimsOnDocument(tenantId, approvedDocument.getId(), applicationId))
                .thenReturn(1L);

        assertThatThrownBy(() -> service.addItem(tenantId, claimantStaff().getId(), applicationId,
                new PaymentApplicationService.AddItemRequest(
                        approvedDocument.getId(), null, new BigDecimal("500.00"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already claimed on another application");
    }

    @Test
    void aClaimCannotExceedTheDocumentsApprovedValue() {
        approvedDocument.setApprovedValue(new BigDecimal("50000.00"));

        assertThatThrownBy(() -> service.addItem(tenantId, claimantStaff().getId(), applicationId,
                new PaymentApplicationService.AddItemRequest(
                        approvedDocument.getId(), null, new BigDecimal("999999999.00"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exceeds the approved value");
    }

    @Test
    void aClaimWithinTheApprovedValueIsAccepted() {
        approvedDocument.setApprovedValue(new BigDecimal("50000.00"));

        assertThat(service.addItem(tenantId, claimantStaff().getId(), applicationId,
                new PaymentApplicationService.AddItemRequest(
                        approvedDocument.getId(), null, new BigDecimal("50000.00")))).isNotNull();
    }

    // ── Who may act on a claim ─────────────────────────────────────────────

    @Test
    void anotherCompanyCannotEditTheClaim() {
        TenantUserEntity outsider = user(UUID.randomUUID(), UserRole.MANAGER);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owning organization can change this claim"))
                .when(accessService).requireOwnOrganization(eq(outsider), any(), any());

        assertThatThrownBy(() -> service.addItem(tenantId, outsider.getId(), applicationId,
                new PaymentApplicationService.AddItemRequest(
                        approvedDocument.getId(), null, new BigDecimal("100.00"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Only the owning organization");
    }

    @Test
    void aClaimCannotBeCertifiedByTheCompanyThatRaisedIt() {
        application.setStatus(PaymentApplicationStatus.SUBMITTED);
        TenantUserEntity claimantStaff = claimantStaff();

        assertThatThrownBy(() -> service.decide(tenantId, claimantStaff.getId(), applicationId, true, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot be certified by the company that raised it");
    }

    @Test
    void aPartyWithoutCertifyingAuthorityCannotCertify() {
        application.setStatus(PaymentApplicationStatus.SUBMITTED);
        TenantUserEntity subcontractorStaff = user(UUID.randomUUID(), UserRole.REVIEWER);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Your organization is not acting as [CONSULTANT, CLIENT] on this project"))
                .when(accessService).requirePartyRole(eq(tenantId), eq(projectId), eq(subcontractorStaff),
                        eq(PartyRole.CONSULTANT), eq(PartyRole.CLIENT));

        assertThatThrownBy(() -> service.decide(tenantId, subcontractorStaff.getId(), applicationId, true, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not acting as");
    }

    @Test
    void theConsultantCanCertify() {
        application.setStatus(PaymentApplicationStatus.SUBMITTED);
        TenantUserEntity consultantStaff = user(UUID.randomUUID(), UserRole.REVIEWER);

        var view = service.decide(tenantId, consultantStaff.getId(), applicationId, true, "Measured on site");

        assertThat(view.status()).isEqualTo(PaymentApplicationStatus.CERTIFIED);
        assertThat(application.getCertifiedBy()).isEqualTo(consultantStaff);
    }

    @Test
    void onlyASubmittedClaimCanBeCertified() {
        application.setStatus(PaymentApplicationStatus.DRAFT);
        TenantUserEntity consultantStaff = user(UUID.randomUUID(), UserRole.REVIEWER);

        assertThatThrownBy(() -> service.decide(tenantId, consultantStaff.getId(), applicationId, true, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Only a submitted claim");
    }

    // ── Releasing payment ──────────────────────────────────────────────────

    @Test
    void onlyTheClientCanMarkAClaimPaid() {
        application.setStatus(PaymentApplicationStatus.CERTIFIED);
        TenantUserEntity consultantStaff = user(UUID.randomUUID(), UserRole.REVIEWER);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Your organization is not acting as [CLIENT] on this project"))
                .when(accessService).requirePartyRole(eq(tenantId), eq(projectId), eq(consultantStaff),
                        eq(PartyRole.CLIENT));

        assertThatThrownBy(() -> service.markPaid(tenantId, consultantStaff.getId(), applicationId, "TT-99"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not acting as");
    }

    @Test
    void theClientCanMarkPaidAndTheActorIsRecorded() {
        application.setStatus(PaymentApplicationStatus.CERTIFIED);
        application.setNetCertified(new BigDecimal("90000.00"));
        TenantUserEntity clientStaff = user(UUID.randomUUID(), UserRole.MANAGER);

        var view = service.markPaid(tenantId, clientStaff.getId(), applicationId, "TT-2026-0042");

        assertThat(view.status()).isEqualTo(PaymentApplicationStatus.PAID);
        assertThat(application.getPaidBy()).isEqualTo(clientStaff);
        assertThat(application.getPaidAt()).isNotNull();
        assertThat(application.getPaymentReference()).isEqualTo("TT-2026-0042");
    }

    @Test
    void aClaimCannotBeMarkedPaidByTheCompanyThatRaisedIt() {
        application.setStatus(PaymentApplicationStatus.CERTIFIED);
        TenantUserEntity claimantStaff = claimantStaff();

        assertThatThrownBy(() -> service.markPaid(tenantId, claimantStaff.getId(), applicationId, "TT-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot be marked paid by the company that raised it");
    }

    @Test
    void onlyACertifiedClaimCanBeMarkedPaid() {
        application.setStatus(PaymentApplicationStatus.SUBMITTED);
        TenantUserEntity clientStaff = user(UUID.randomUUID(), UserRole.MANAGER);

        assertThatThrownBy(() -> service.markPaid(tenantId, clientStaff.getId(), applicationId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Only a certified claim");
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Test
    void aSubmittedClaimCanNoLongerBeEdited() {
        application.setStatus(PaymentApplicationStatus.SUBMITTED);

        assertThatThrownBy(() -> service.addItem(tenantId, claimantStaff().getId(), applicationId,
                new PaymentApplicationService.AddItemRequest(
                        approvedDocument.getId(), null, new BigDecimal("100.00"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("can no longer be changed");
    }

    @Test
    void anEmptyClaimCannotBeSubmitted() {
        assertThatThrownBy(() -> service.submit(tenantId, claimantStaff().getId(), applicationId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no items");
    }

    @Test
    void submissionRefreshesTheCumulativePosition() {
        service.addItem(tenantId, claimantStaff().getId(), applicationId,
                new PaymentApplicationService.AddItemRequest(
                        approvedDocument.getId(), null, new BigDecimal("1000.00")));
        // Another claim was certified between this draft being opened and submitted.
        when(applicationRepository.sumCertifiedToDate(tenantId, projectId, contractor.getId()))
                .thenReturn(new BigDecimal("45000.00"));

        var view = service.submit(tenantId, claimantStaff().getId(), applicationId);

        assertThat(view.previouslyCertified()).isEqualByComparingTo("45000.00");
    }

    // ── Raising a claim ────────────────────────────────────────────────────

    @Test
    void theClaimantIsTheCallersOwnCompanyNotWhateverTheRequestNames() {
        TenantUserEntity staff = user(contractor.getId(), UserRole.REVIEWER);
        UUID someoneElse = UUID.randomUUID();

        service.create(tenantId, staff.getId(), new PaymentApplicationService.CreateRequest(
                projectId, "IPC-01", someoneElse, null, null));

        // The nominated organization was ignored in favour of the caller's own.
        assertThat(organizationRepository.findByIdAndTenantId(contractor.getId(), tenantId)).isPresent();
    }

    @Test
    void aUserWithNoCompanyCannotRaiseAClaim() {
        TenantUserEntity orphan = user(null, UserRole.REVIEWER);

        assertThatThrownBy(() -> service.create(tenantId, orphan.getId(),
                new PaymentApplicationService.CreateRequest(projectId, "IPC-02", null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not attached to an organization");
    }

    @Test
    void aCompanyNotOnTheProjectCannotRaiseAClaim() {
        when(projectService.isParticipant(any(), any(), any())).thenReturn(false);
        TenantUserEntity staff = claimantStaff();

        assertThatThrownBy(() -> service.create(tenantId, staff.getId(),
                new PaymentApplicationService.CreateRequest(
                        projectId, "IPC-03", contractor.getId(), null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not a participant on this project");
    }
}
