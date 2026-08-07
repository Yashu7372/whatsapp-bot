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
import com.whatsappbot.project.ProjectAccessService;
import com.whatsappbot.project.PartyRole;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final ProjectAccessService accessService;
    private final PaymentAuditService auditService;

    // ── Creating and building a claim ──────────────────────────────────────

    @Transactional
    public ApplicationView create(UUID tenantId, UUID userId, CreateRequest req) {
        TenantUserEntity actor = accessService.requireActiveUser(tenantId, userId);
        ProjectEntity project = projectService.get(tenantId, req.projectId());
        accessService.requireProjectVisibility(tenantId, project.getId(), actor);

        if (applicationRepository.existsByProjectIdAndApplicationRefIgnoreCase(
                project.getId(), req.applicationRef())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A payment application '" + req.applicationRef() + "' already exists on this project");
        }

        // The claimant is the caller's own company. Taking it from the request let a user at
        // one contractor raise a claim in another contractor's name — the same mistake the
        // document layer already avoids by deriving the originator from the logged-in user.
        // Tenant administrators may still claim on behalf of a company they nominate.
        UUID claimantOrgId = accessService.isTenantAdministrator(actor) && req.claimedByOrgId() != null
                ? req.claimedByOrgId()
                : actor.getOrganizationId();
        if (claimantOrgId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your user is not attached to an organization, so it cannot raise a claim");
        }

        OrganizationEntity claimant = organizationRepository
                .findByIdAndTenantId(claimantOrgId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Organization not found: " + claimantOrgId));

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

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("applicationRef", saved.getApplicationRef());
        payload.put("projectId", project.getId().toString());
        payload.put("claimedByOrg", claimant.getName());
        payload.put("retentionPercent", saved.getRetentionPercent().toPlainString());
        payload.put("previouslyCertified", saved.getPreviouslyCertified().toPlainString());
        auditService.record(tenantId, saved.getId(), userId, PaymentAuditService.PAYMENT_CREATED, payload);

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
    public ItemView addItem(UUID tenantId, UUID userId, UUID applicationId, AddItemRequest req) {
        PaymentApplicationEntity application = getEditable(tenantId, applicationId);
        TenantUserEntity actor = requireClaimantStaff(tenantId, userId, application, "change this claim");

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
        // The per-application unique key only stopped a document appearing twice on one claim.
        // Nothing stopped the same approved work being claimed in full on three claims in a row.
        if (applicationRepository.countLiveClaimsOnDocument(tenantId, document.getId(), applicationId) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Document " + displayName(document) + " is already claimed on another application");
        }
        if (req.amount() == null || req.amount().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "amount must be zero or greater");
        }
        // Without a ceiling the only validation was "not negative", so an approved document
        // could carry a claim of any size.
        BigDecimal ceiling = document.getApprovedValue();
        if (ceiling != null && req.amount().compareTo(ceiling) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Claimed amount exceeds the approved value of " + displayName(document)
                            + " (" + ceiling.toPlainString() + ")");
        }

        PaymentApplicationItemEntity item = new PaymentApplicationItemEntity();
        item.setTenant(application.getTenant());
        item.setPaymentApplicationId(applicationId);
        item.setDocumentId(document.getId());
        item.setDescription(req.description() != null ? req.description() : document.getTitle());
        item.setAmount(req.amount().setScale(MONEY_SCALE, RoundingMode.HALF_UP));

        PaymentApplicationItemEntity saved = itemRepository.save(item);
        recalculate(application);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("itemId", saved.getId().toString());
        payload.put("documentId", document.getId().toString());
        payload.put("documentCode", document.getDocumentCode());
        payload.put("amount", saved.getAmount().toPlainString());
        payload.put("grossAfter", application.getGrossClaimed().toPlainString());
        payload.put("actorEmail", actor.getEmail());
        auditService.record(tenantId, applicationId, userId, PaymentAuditService.PAYMENT_ITEM_ADDED, payload);

        return toView(saved);
    }

    @Transactional
    public void removeItem(UUID tenantId, UUID userId, UUID applicationId, UUID itemId) {
        PaymentApplicationEntity application = getEditable(tenantId, applicationId);
        TenantUserEntity actor = requireClaimantStaff(tenantId, userId, application, "change this claim");
        PaymentApplicationItemEntity item = itemRepository.findByIdAndTenantId(itemId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Item not found: " + itemId));
        if (!item.getPaymentApplicationId().equals(applicationId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That item belongs to a different application");
        }
        itemRepository.delete(item);
        recalculate(application);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("itemId", itemId.toString());
        payload.put("documentId", item.getDocumentId() != null ? item.getDocumentId().toString() : null);
        payload.put("amount", item.getAmount().toPlainString());
        payload.put("grossAfter", application.getGrossClaimed().toPlainString());
        payload.put("actorEmail", actor.getEmail());
        auditService.record(tenantId, applicationId, userId, PaymentAuditService.PAYMENT_ITEM_REMOVED, payload);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Transactional
    public ApplicationView submit(UUID tenantId, UUID userId, UUID applicationId) {
        PaymentApplicationEntity application = getEditable(tenantId, applicationId);
        TenantUserEntity actor = requireClaimantStaff(tenantId, userId, application, "submit this claim");

        if (itemRepository
                .findAllByTenantIdAndPaymentApplicationIdOrderByCreatedAtAsc(tenantId, applicationId)
                .isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot submit a claim with no items");
        }

        // Refreshed at submission rather than left as it was at creation. Two drafts opened on
        // the same day both recorded a zero opening position; if one was certified in between,
        // the other would still claim to start from zero and overstate the cumulative figure.
        application.setPreviouslyCertified(applicationRepository.sumCertifiedToDate(
                tenantId, application.getProjectId(), application.getClaimedByOrg().getId()));

        application.setStatus(PaymentApplicationStatus.SUBMITTED);
        application.setSubmittedAt(LocalDateTime.now());
        PaymentApplicationEntity saved = applicationRepository.save(application);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("grossClaimed", saved.getGrossClaimed().toPlainString());
        payload.put("retentionAmount", saved.getRetentionAmount().toPlainString());
        payload.put("netClaimed", saved.getNetCertified().toPlainString());
        payload.put("previouslyCertified", saved.getPreviouslyCertified().toPlainString());
        payload.put("actorEmail", actor.getEmail());
        auditService.record(tenantId, applicationId, userId, PaymentAuditService.PAYMENT_SUBMITTED, payload);

        return toView(saved);
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

        TenantUserEntity certifier = accessService.requireActiveUser(tenantId, userId);

        if (certifier.getOrganizationId() != null
                && certifier.getOrganizationId().equals(application.getClaimedByOrg().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "A claim cannot be certified by the company that raised it");
        }

        // "Not the claimant" is far too weak on its own — it would let a subcontractor on the
        // same project certify the main contractor's money. Certification belongs to the parties
        // that administer the contract.
        accessService.requirePartyRole(tenantId, application.getProjectId(), certifier,
                PartyRole.CONSULTANT, PartyRole.CLIENT);

        application.setStatus(certify
                ? PaymentApplicationStatus.CERTIFIED
                : PaymentApplicationStatus.REJECTED);
        application.setCertifiedBy(certifier);
        application.setCertifiedAt(LocalDateTime.now());

        PaymentApplicationEntity saved = applicationRepository.save(application);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decision", certify ? "CERTIFIED" : "REJECTED");
        payload.put("grossClaimed", saved.getGrossClaimed().toPlainString());
        payload.put("retentionAmount", saved.getRetentionAmount().toPlainString());
        payload.put("netCertified", saved.getNetCertified().toPlainString());
        payload.put("comments", comments);
        payload.put("certifiedByEmail", certifier.getEmail());
        auditService.record(tenantId, applicationId, userId,
                certify ? PaymentAuditService.PAYMENT_CERTIFIED : PaymentAuditService.PAYMENT_REJECTED,
                payload);

        log.info("Payment application {} {} by user={}", applicationId,
                certify ? "certified" : "rejected", userId);
        return toView(saved);
    }

    /**
     * Records that a certified claim has been settled.
     *
     * <p>This releases money, and it previously took no actor at all — any authenticated user in
     * the tenant could mark a claim paid, and nothing recorded who did. Payment is the client's
     * act, so it is restricted to the client's staff (or a tenant administrator) and written to
     * the audit chain with the actor and the external payment reference.
     */
    @Transactional
    public ApplicationView markPaid(UUID tenantId, UUID userId, UUID applicationId,
                                     String paymentReference) {
        PaymentApplicationEntity application = get(tenantId, applicationId);
        if (application.getStatus() != PaymentApplicationStatus.CERTIFIED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a certified claim can be marked paid; this one is " + application.getStatus());
        }

        TenantUserEntity actor = accessService.requireActiveUser(tenantId, userId);
        accessService.requirePartyRole(tenantId, application.getProjectId(), actor, PartyRole.CLIENT);

        if (actor.getOrganizationId() != null
                && actor.getOrganizationId().equals(application.getClaimedByOrg().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "A claim cannot be marked paid by the company that raised it");
        }

        application.setStatus(PaymentApplicationStatus.PAID);
        application.setPaidBy(actor);
        application.setPaidAt(LocalDateTime.now());
        application.setPaymentReference(paymentReference);
        PaymentApplicationEntity saved = applicationRepository.save(application);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("netCertified", saved.getNetCertified().toPlainString());
        payload.put("currency", saved.getCurrency());
        payload.put("paymentReference", paymentReference);
        payload.put("paidByEmail", actor.getEmail());
        auditService.record(tenantId, applicationId, userId,
                PaymentAuditService.PAYMENT_MARKED_PAID, payload);

        log.info("Payment application {} marked paid by user={} ref={}",
                applicationId, userId, paymentReference);
        return toView(saved);
    }

    /**
     * Requires the caller to work for the company that raised the claim.
     *
     * <p>Building and submitting a claim is the claimant's own act. Without this, one contractor
     * could add lines to, or submit, another contractor's draft.
     */
    private TenantUserEntity requireClaimantStaff(UUID tenantId, UUID userId,
                                                   PaymentApplicationEntity application,
                                                   String action) {
        TenantUserEntity actor = accessService.requireActiveUser(tenantId, userId);
        accessService.requireOwnOrganization(actor, application.getClaimedByOrg().getId(), action);
        return actor;
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ApplicationView> list(UUID tenantId, UUID userId, UUID projectId) {
        projectService.get(tenantId, projectId);
        accessService.requireProjectVisibility(tenantId, projectId,
                accessService.requireActiveUser(tenantId, userId));
        return applicationRepository.findAllByTenantIdAndProjectIdOrderByCreatedAtDesc(tenantId, projectId)
                .stream().map(this::toView).toList();
    }

    /** Single claim, mapped inside the transaction for the same LAZY-association reason. */
    @Transactional(readOnly = true)
    public ApplicationView getView(UUID tenantId, UUID userId, UUID applicationId) {
        PaymentApplicationEntity application = get(tenantId, applicationId);
        accessService.requireProjectVisibility(tenantId, application.getProjectId(),
                accessService.requireActiveUser(tenantId, userId));
        return toView(application);
    }

    @Transactional(readOnly = true)
    public PaymentApplicationEntity get(UUID tenantId, UUID applicationId) {
        return applicationRepository.findByIdAndTenantId(applicationId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Payment application not found: " + applicationId));
    }

    @Transactional(readOnly = true)
    public List<ItemView> listItems(UUID tenantId, UUID userId, UUID applicationId) {
        PaymentApplicationEntity application = get(tenantId, applicationId);
        accessService.requireProjectVisibility(tenantId, application.getProjectId(),
                accessService.requireActiveUser(tenantId, userId));
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
