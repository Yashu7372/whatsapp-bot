package com.whatsappbot.payment;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.TenantUserRepository;
import com.whatsappbot.document.DocumentEntity;
import com.whatsappbot.document.DocumentRepository;
import com.whatsappbot.document.DocumentStatus;
import com.whatsappbot.document.ReviewOutcome;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.project.OrganizationEntity;
import com.whatsappbot.project.OrganizationRepository;
import com.whatsappbot.project.ProjectEntity;
import com.whatsappbot.project.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Payment claims, built from the documents that evidence the work.
 *
 * <p>The rule that gives the module its value: an amount cannot be claimed against a document
 * that has not been approved. That is the link the email-and-spreadsheet process cannot enforce,
 * and it is why certified figures here can be defended later.
 *
 * <p>Figures are per period rather than cumulative — {@code grossClaimed} is the value of the work
 * in this application, and {@code previouslyCertified} carries the running total certified before
 * it, so a claim can be read on its own or as part of the sequence.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentApplicationService {

    /** Money is held to fils/cents; retention is derived, so it rounds once, here. */
    private static final int MONEY_SCALE = 2;
    private static final BigDecimal PERCENT_DIVISOR = new BigDecimal("100");

    private final PaymentApplicationRepository applicationRepository;
    private final PaymentApplicationItemRepository itemRepository;
    private final DocumentRepository documentRepository;
    private final OrganizationRepository organizationRepository;
    private final TenantUserRepository userRepository;
    private final ProjectService projectService;

    // ── Creating and building a claim ──────────────────────────────────────

    @Transactional
    public ApplicationView create(UUID tenantId, UUID userId, CreateRequest req) {
        ProjectEntity project = projectService.get(tenantId, req.projectId());

        if (applicationRepository.existsByProjectIdAndApplicationRefIgnoreCase(
                project.getId(), req.applicationRef())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A payment application '" + req.applicationRef() + "' already exists on this project");
        }

        OrganizationEntity claimant = organizationRepository
                .findByIdAndTenantId(req.claimedByOrgId(), tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Organization not found: " + req.claimedByOrgId()));

        // Only a company actually engaged on the project can claim against it.
        if (!projectService.isParticipant(tenantId, project.getId(), claimant.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    claimant.getName() + " is not a participant on this project");
        }

        PaymentApplicationEntity application = new PaymentApplicationEntity();
        application.setTenant(project.getTenant());
        application.setProjectId(project.getId());
        application.setApplicationRef(req.applicationRef());
        application.setClaimedByOrg(claimant);
        application.setPeriodStart(req.periodStart());
        application.setPeriodEnd(req.periodEnd());
        application.setCurrency(project.getCurrency());
        // Taken from the project's contract terms rather than supplied by the claimant.
        application.setRetentionPercent(project.getRetentionPercent());
        application.setPreviouslyCertified(
                applicationRepository.sumCertifiedToDate(tenantId, project.getId(), claimant.getId()));
        application.setCreatedBy(userRepository.findById(userId).orElse(null));

        PaymentApplicationEntity saved = applicationRepository.save(application);
        log.info("Payment application created. id={} ref={} project={}",
                saved.getId(), req.applicationRef(), project.getId());
        return toView(saved);
    }

    /**
     * Adds a line backed by an approved document.
     *
     * <p>A document is claimable once its approval finished and the reviewer did not ask for a
     * resubmission — CODE_A and CODE_B both let work proceed, CODE_C and CODE_D do not.
     */
    @Transactional
    public ItemView addItem(UUID tenantId, UUID applicationId, AddItemRequest req) {
        PaymentApplicationEntity application = getEditable(tenantId, applicationId);

        DocumentEntity document = documentRepository
                .findByIdAndTenantId(req.documentId(), tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Document not found: " + req.documentId()));

        assertBelongsToSameProject(application, document);
        assertClaimable(document);

        if (itemRepository.existsByPaymentApplicationIdAndDocumentId(applicationId, document.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This document is already claimed on this application");
        }
        if (req.amount() == null || req.amount().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "amount must be zero or greater");
        }

        PaymentApplicationItemEntity item = new PaymentApplicationItemEntity();
        item.setTenant(application.getTenant());
        item.setPaymentApplicationId(applicationId);
        item.setDocumentId(document.getId());
        item.setDescription(req.description() != null ? req.description() : document.getTitle());
        item.setAmount(req.amount().setScale(MONEY_SCALE, RoundingMode.HALF_UP));

        PaymentApplicationItemEntity saved = itemRepository.save(item);
        recalculate(application);
        return toView(saved);
    }

    @Transactional
    public void removeItem(UUID tenantId, UUID applicationId, UUID itemId) {
        PaymentApplicationEntity application = getEditable(tenantId, applicationId);
        PaymentApplicationItemEntity item = itemRepository.findByIdAndTenantId(itemId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Item not found: " + itemId));
        if (!item.getPaymentApplicationId().equals(applicationId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That item belongs to a different application");
        }
        itemRepository.delete(item);
        recalculate(application);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Transactional
    public ApplicationView submit(UUID tenantId, UUID applicationId) {
        PaymentApplicationEntity application = getEditable(tenantId, applicationId);
        if (itemRepository
                .findAllByTenantIdAndPaymentApplicationIdOrderByCreatedAtAsc(tenantId, applicationId)
                .isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot submit a claim with no items");
        }
        application.setStatus(PaymentApplicationStatus.SUBMITTED);
        application.setSubmittedAt(LocalDateTime.now());
        return toView(applicationRepository.save(application));
    }

    /**
     * Certifies or declines a submitted claim.
     *
     * <p>The certifier must not be the party claiming: a company cannot certify its own
     * application, which is the whole point of the client/consultant/contractor separation.
     */
    @Transactional
    public ApplicationView decide(UUID tenantId, UUID userId, UUID applicationId,
                                   boolean certify, String comments) {
        PaymentApplicationEntity application = get(tenantId, applicationId);

        if (application.getStatus() != PaymentApplicationStatus.SUBMITTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a submitted claim can be certified; this one is "
                            + application.getStatus());
        }

        TenantUserEntity certifier = userRepository.findById(userId)
                .filter(u -> u.getTenant().getId().equals(tenantId))
                .filter(TenantUserEntity::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "User is not an active member of this tenant"));

        if (certifier.getOrganizationId() != null
                && certifier.getOrganizationId().equals(application.getClaimedByOrg().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "A claim cannot be certified by the company that raised it");
        }

        application.setStatus(certify
                ? PaymentApplicationStatus.CERTIFIED
                : PaymentApplicationStatus.REJECTED);
        application.setCertifiedBy(certifier);
        application.setCertifiedAt(LocalDateTime.now());

        log.info("Payment application {} {} by user={}", applicationId,
                certify ? "certified" : "rejected", userId);
        return toView(applicationRepository.save(application));
    }

    @Transactional
    public ApplicationView markPaid(UUID tenantId, UUID applicationId) {
        PaymentApplicationEntity application = get(tenantId, applicationId);
        if (application.getStatus() != PaymentApplicationStatus.CERTIFIED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a certified claim can be marked paid; this one is " + application.getStatus());
        }
        application.setStatus(PaymentApplicationStatus.PAID);
        return toView(applicationRepository.save(application));
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ApplicationView> list(UUID tenantId, UUID projectId) {
        projectService.get(tenantId, projectId);
        return applicationRepository.findAllByTenantIdAndProjectIdOrderByCreatedAtDesc(tenantId, projectId)
                .stream().map(this::toView).toList();
    }

    /** Single claim, mapped inside the transaction for the same LAZY-association reason. */
    @Transactional(readOnly = true)
    public ApplicationView getView(UUID tenantId, UUID applicationId) {
        return toView(get(tenantId, applicationId));
    }

    @Transactional(readOnly = true)
    public PaymentApplicationEntity get(UUID tenantId, UUID applicationId) {
        return applicationRepository.findByIdAndTenantId(applicationId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Payment application not found: " + applicationId));
    }

    @Transactional(readOnly = true)
    public List<ItemView> listItems(UUID tenantId, UUID applicationId) {
        get(tenantId, applicationId);
        return itemRepository.findAllByTenantIdAndPaymentApplicationIdOrderByCreatedAtAsc(
                        tenantId, applicationId)
                .stream().map(PaymentApplicationService::toView).toList();
    }

    // ── Internals ──────────────────────────────────────────────────────────

    /**
     * Recomputes the claim from its lines. Retention comes off the gross at the project's
     * contract rate, leaving the net payable for the period.
     */
    private void recalculate(PaymentApplicationEntity application) {
        BigDecimal gross = itemRepository
                .findAllByTenantIdAndPaymentApplicationIdOrderByCreatedAtAsc(
                        application.getTenant().getId(), application.getId())
                .stream()
                .map(PaymentApplicationItemEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        BigDecimal retention = gross
                .multiply(application.getRetentionPercent())
                .divide(PERCENT_DIVISOR, MONEY_SCALE, RoundingMode.HALF_UP);

        application.setGrossClaimed(gross);
        application.setRetentionAmount(retention);
        application.setNetCertified(gross.subtract(retention).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        applicationRepository.save(application);
    }

    private PaymentApplicationEntity getEditable(UUID tenantId, UUID applicationId) {
        PaymentApplicationEntity application = get(tenantId, applicationId);
        if (!application.getStatus().isEditable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + application.getStatus() + " claim can no longer be changed");
        }
        return application;
    }

    private void assertBelongsToSameProject(PaymentApplicationEntity application, DocumentEntity document) {
        if (document.getProjectId() == null || !document.getProjectId().equals(application.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That document belongs to a different project");
        }
    }

    private void assertClaimable(DocumentEntity document) {
        if (document.getStatus() != DocumentStatus.APPROVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Document " + displayName(document) + " is " + document.getStatus()
                            + "; only an approved document can be claimed against");
        }
        ReviewOutcome outcome = document.getReviewOutcome();
        if (outcome != null && outcome.isResubmissionRequired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Document " + displayName(document) + " was returned " + outcome
                            + " and needs resubmission before it can be claimed");
        }
    }

    private String displayName(DocumentEntity document) {
        return document.getDocumentCode() != null ? document.getDocumentCode() : document.getTitle();
    }

    /**
     * Maps inside the transaction. The claimant organization and both user references are LAZY
     * associations, so building this view in a controller would throw once the session closed.
     */
    ApplicationView toView(PaymentApplicationEntity a) {
        return new ApplicationView(
                a.getId(), a.getProjectId(), a.getApplicationRef(),
                a.getClaimedByOrg().getId(), a.getClaimedByOrg().getName(),
                a.getPeriodStart(), a.getPeriodEnd(),
                a.getGrossClaimed(), a.getPreviouslyCertified(),
                a.getRetentionPercent(), a.getRetentionAmount(), a.getNetCertified(),
                a.getCurrency(), a.getStatus(), a.getSubmittedAt(),
                a.getCertifiedBy() != null ? a.getCertifiedBy().getEmail() : null,
                a.getCertifiedAt(), a.getCreatedAt());
    }

    static ItemView toView(PaymentApplicationItemEntity i) {
        return new ItemView(i.getId(), i.getPaymentApplicationId(), i.getDocumentId(),
                i.getDescription(), i.getAmount(), i.getCreatedAt());
    }

    public record ApplicationView(UUID id, UUID projectId, String applicationRef,
                                   UUID claimedByOrgId, String claimedByOrgName,
                                   LocalDate periodStart, LocalDate periodEnd,
                                   BigDecimal grossClaimed, BigDecimal previouslyCertified,
                                   BigDecimal retentionPercent, BigDecimal retentionAmount,
                                   BigDecimal netCertified, String currency,
                                   PaymentApplicationStatus status, LocalDateTime submittedAt,
                                   String certifiedByEmail, LocalDateTime certifiedAt,
                                   LocalDateTime createdAt) {}

    public record ItemView(UUID id, UUID paymentApplicationId, UUID documentId, String description,
                            BigDecimal amount, LocalDateTime createdAt) {}

    public record CreateRequest(UUID projectId, String applicationRef, UUID claimedByOrgId,
                                 LocalDate periodStart, LocalDate periodEnd) {}

    public record AddItemRequest(UUID documentId, String description, BigDecimal amount) {}
}
