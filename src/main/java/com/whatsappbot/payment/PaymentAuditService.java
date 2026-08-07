package com.whatsappbot.payment;

import com.whatsappbot.audit.AuditChainHasher;
import com.whatsappbot.auth.TenantUserRepository;
import com.whatsappbot.domain.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tamper-evident history of a payment claim.
 *
 * <p>Document approval was already recorded this way, but the money it authorises was not — so
 * the evidence chain stopped exactly where it started to matter. Every step from raising a claim
 * to releasing payment is recorded here with the same linked hashes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentAuditService {

    private final PaymentAuditEventRepository auditEventRepository;
    private final PaymentApplicationRepository applicationRepository;
    private final TenantRepository tenantRepository;
    private final TenantUserRepository userRepository;
    private final AuditChainHasher hasher;

    /**
     * Appends an event to a claim's chain.
     *
     * <p>Locks the application row first: reading the previous hash and writing the next event is
     * a read-modify-write, so concurrent events would otherwise fork the chain — and a fork
     * cannot be told apart from tampering.
     */
    @Transactional
    public PaymentAuditEventEntity record(UUID tenantId, UUID applicationId, UUID actorUserId,
                                           String eventType, Map<String, Object> payload) {
        applicationRepository.lockById(applicationId);

        PaymentAuditEventEntity event = new PaymentAuditEventEntity();
        event.setTenant(tenantRepository.getReferenceById(tenantId));
        event.setPaymentApplicationId(applicationId);
        if (actorUserId != null) {
            event.setActorUser(userRepository.findById(actorUserId).orElse(null));
        }
        event.setEventType(eventType);
        event.setEventPayload(payload);

        LocalDateTime occurredAt = LocalDateTime.now();
        event.setCreatedAt(occurredAt);

        String previousHash = auditEventRepository.findLatest(tenantId, applicationId)
                .map(PaymentAuditEventEntity::getEventHash)
                .orElse(AuditChainHasher.GENESIS_HASH);
        event.setPreviousEventHash(previousHash);
        event.setEventHash(hasher.hash(tenantId, applicationId, eventType, actorUserId,
                occurredAt, payload, previousHash));

        return auditEventRepository.save(event);
    }

    @Transactional(readOnly = true)
    public List<AuditEventView> getAuditTrail(UUID tenantId, UUID applicationId) {
        return auditEventRepository
                .findAllByTenantIdAndPaymentApplicationIdOrderByCreatedAtAsc(tenantId, applicationId)
                .stream()
                .map(e -> new AuditEventView(
                        e.getId(), e.getPaymentApplicationId(), e.getEventType(),
                        e.getActorUser() != null ? e.getActorUser().getEmail() : null,
                        e.getEventPayload(), e.getEventHash(), e.getPreviousEventHash(),
                        e.getCreatedAt()))
                .toList();
    }

    public record AuditEventView(UUID id, UUID paymentApplicationId, String eventType,
                                  String actorEmail, Map<String, Object> payload, String eventHash,
                                  String previousEventHash, LocalDateTime createdAt) {}

    // ── Event types ───────────────────────────────────────────────────────

    public static final String PAYMENT_CREATED      = "PAYMENT_CREATED";
    public static final String PAYMENT_ITEM_ADDED   = "PAYMENT_ITEM_ADDED";
    public static final String PAYMENT_ITEM_REMOVED = "PAYMENT_ITEM_REMOVED";
    public static final String PAYMENT_SUBMITTED    = "PAYMENT_SUBMITTED";
    public static final String PAYMENT_CERTIFIED    = "PAYMENT_CERTIFIED";
    public static final String PAYMENT_REJECTED     = "PAYMENT_REJECTED";
    public static final String PAYMENT_MARKED_PAID  = "PAYMENT_MARKED_PAID";
}
