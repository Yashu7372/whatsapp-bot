package com.whatsappbot.document;

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

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentAuditService {

    private final DocumentAuditEventRepository auditEventRepository;
    private final DocumentRepository documentRepository;
    private final TenantRepository tenantRepository;
    private final TenantUserRepository userRepository;
    private final AuditChainHasher hasher;

    /**
     * Appends an event to a document's chain.
     *
     * <p>Takes a write lock on the document first. Reading the previous hash and writing the next
     * event is a read-modify-write, so two concurrent events — an approval and a version upload,
     * say — would otherwise both read the same predecessor and produce two entries claiming the
     * same position. The chain would fork, and a fork is indistinguishable from tampering.
     * Serialising on the parent row means the second writer waits and links onto the first.
     */
    @Transactional
    public DocumentAuditEventEntity record(UUID tenantId, UUID documentId,
                                            UUID actorUserId, String eventType,
                                            Map<String, Object> payload) {
        documentRepository.lockById(documentId);

        DocumentAuditEventEntity event = new DocumentAuditEventEntity();
        event.setTenant(tenantRepository.getReferenceById(tenantId));
        event.setDocumentId(documentId);
        if (actorUserId != null) {
            event.setActorUser(userRepository.findById(actorUserId).orElse(null));
        }
        event.setEventType(eventType);
        event.setEventPayload(payload);

        // Set here rather than leaving it to @PrePersist: the hash covers it, and @PrePersist
        // only fires at flush — after this method returns.
        LocalDateTime occurredAt = LocalDateTime.now();
        event.setCreatedAt(occurredAt);

        String previousHash = auditEventRepository
                .findLatestByDocumentId(tenantId, documentId)
                .map(DocumentAuditEventEntity::getEventHash)
                .orElse(AuditChainHasher.GENESIS_HASH);
        event.setPreviousEventHash(previousHash);
        event.setEventHash(hasher.hash(tenantId, documentId, eventType, actorUserId,
                occurredAt, payload, previousHash));

        return auditEventRepository.save(event);
    }

    /**
     * The document's history, mapped inside the transaction because the actor is a LAZY
     * association.
     *
     * <p>Scoped by tenant: the trail exposes actor emails and payloads, and document ids are
     * guessable, so an unscoped lookup would let one tenant read another's approval history.
     *
     * <p>Both hashes are exposed so a caller can re-link the chain and check it is intact.
     */
    @Transactional(readOnly = true)
    public List<AuditEventView> getAuditTrail(UUID tenantId, UUID documentId) {
        return auditEventRepository.findAllByTenantIdAndDocumentIdOrderByCreatedAtAsc(tenantId, documentId)
                .stream()
                .map(e -> new AuditEventView(
                        e.getId(), e.getDocumentId(), e.getEventType(),
                        e.getActorUser() != null ? e.getActorUser().getEmail() : null,
                        e.getEventPayload(), e.getEventHash(), e.getPreviousEventHash(),
                        e.getCreatedAt()))
                .toList();
    }

    public record AuditEventView(UUID id, UUID documentId, String eventType, String actorEmail,
                                  Map<String, Object> payload, String eventHash,
                                  String previousEventHash, LocalDateTime createdAt) {}

    // ── Event type constants ──────────────────────────────────────────────

    public static final String DOCUMENT_CREATED            = "DOCUMENT_CREATED";
    public static final String VERSION_UPLOADED            = "VERSION_UPLOADED";
    public static final String DOCUMENT_SUBMITTED          = "DOCUMENT_SUBMITTED";
    public static final String APPROVAL_APPROVED           = "APPROVAL_APPROVED";
    public static final String APPROVAL_REJECTED           = "APPROVAL_REJECTED";
    public static final String DOCUMENT_DOWNLOADED         = "DOCUMENT_DOWNLOADED";
    public static final String DECRYPTION_ATTEMPT_RECORDED = "DECRYPTION_ATTEMPT_RECORDED";
    public static final String SHARE_GRANTED               = "SHARE_GRANTED";
    public static final String SHARE_REVOKED               = "SHARE_REVOKED";
    public static final String DOCUMENT_EXPIRED            = "DOCUMENT_EXPIRED";
    public static final String DOCUMENT_AI_ANALYZED        = "DOCUMENT_AI_ANALYZED";
}
