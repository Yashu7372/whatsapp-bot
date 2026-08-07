package com.whatsappbot.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.whatsappbot.auth.TenantUserRepository;
import com.whatsappbot.domain.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentAuditService {

    /** Marks the first event in a document's chain, which has no predecessor. */
    private static final String GENESIS_HASH = "genesis";

    /** Separates fields in the pre-image so values cannot run together and collide. */
    private static final String HASH_FIELD_SEPARATOR = "|";

    /** Stand-in used when a payload cannot be serialised; keeps the chain unbroken. */
    private static final String UNSERIALISABLE_PAYLOAD = "<unserialisable>";

    /**
     * Sorts map keys so the same logical payload always serialises identically — a hash that
     * depended on attribute ordering could not be recomputed later to verify the chain.
     * Configured once and never mutated, so it is safe to share across threads.
     */
    private static final ObjectMapper CANONICAL_JSON = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private final DocumentAuditEventRepository auditEventRepository;
    private final TenantRepository tenantRepository;
    private final TenantUserRepository userRepository;

    @Transactional
    public DocumentAuditEventEntity record(UUID tenantId, UUID documentId,
                                            UUID actorUserId, String eventType,
                                            Map<String, Object> payload) {
        DocumentAuditEventEntity event = new DocumentAuditEventEntity();
        event.setTenant(tenantRepository.getReferenceById(tenantId));
        event.setDocumentId(documentId);
        if (actorUserId != null) {
            event.setActorUser(userRepository.findById(actorUserId).orElse(null));
        }
        event.setEventType(eventType);
        event.setEventPayload(payload);

        // Set the timestamp here rather than leaving it to @PrePersist: the hash has to
        // cover it, and @PrePersist only fires at flush — after this method returns.
        event.setCreatedAt(LocalDateTime.now());

        String previousHash = auditEventRepository
                .findLatestByDocumentId(tenantId, documentId)
                .map(DocumentAuditEventEntity::getEventHash)
                .orElse(GENESIS_HASH);
        event.setPreviousEventHash(previousHash);
        event.setEventHash(computeHash(event));

        return auditEventRepository.save(event);
    }

    /**
     * Hashes everything that gives the event meaning: who acted, when, and what changed.
     *
     * <p>An earlier version hashed only tenant + document + event type + previous hash. That
     * left the chain unable to detect the two edits that matter most — two approvals on the
     * same document produced identical hashes, and {@code event_payload} (which records who
     * approved and what they said) could be rewritten in the database without breaking it.
     * Since these events are the evidence that work was approved before payment, the hash
     * has to commit to the payload, the actor and the timestamp as well.
     *
     * <p>The payload is serialised with map keys sorted so that a re-hash of the same logical
     * event always produces the same digest regardless of JSON attribute ordering.
     */
    private String computeHash(DocumentAuditEventEntity event) {
        String canonicalPayload;
        try {
            canonicalPayload = event.getEventPayload() == null
                    ? ""
                    : CANONICAL_JSON.writeValueAsString(event.getEventPayload());
        } catch (JsonProcessingException e) {
            // Never drop the event over a serialisation problem — fall back to a marker that
            // still differs per event, so the chain stays continuous and the anomaly is visible.
            log.warn("Could not canonicalise audit payload for document {}: {}",
                    event.getDocumentId(), e.getMessage());
            canonicalPayload = UNSERIALISABLE_PAYLOAD;
        }

        String actorId = event.getActorUser() != null ? event.getActorUser().getId().toString() : "";

        return sha256(String.join(HASH_FIELD_SEPARATOR,
                event.getTenant().getId().toString(),
                event.getDocumentId().toString(),
                event.getEventType(),
                actorId,
                event.getCreatedAt().toString(),
                canonicalPayload,
                event.getPreviousEventHash()));
    }

    /**
     * Returns the trail already mapped for the caller.
     *
     * <p>Mapping happens here rather than in the controller because {@code actorUser} is a LAZY
     * association and {@code open-in-view} is false — reading the actor's email after this
     * transaction closed threw {@code LazyInitializationException}, which meant the endpoint
     * failed for every document that had an actor recorded against it.
     *
     * <p>Scoped by tenant: the trail exposes actor emails and payloads, and document ids are
     * guessable, so an unscoped lookup would let one tenant read another's approval history.
     */
    @Transactional(readOnly = true)
    public List<AuditEventView> getAuditTrail(UUID tenantId, UUID documentId) {
        return auditEventRepository
                .findAllByTenantIdAndDocumentIdOrderByCreatedAtAsc(tenantId, documentId)
                .stream()
                .map(e -> new AuditEventView(
                        e.getId(),
                        e.getDocumentId(),
                        e.getEventType(),
                        e.getActorUser() != null ? e.getActorUser().getEmail() : null,
                        e.getEventPayload(),
                        e.getCreatedAt(),
                        e.getEventHash(),
                        e.getPreviousEventHash()))
                .toList();
    }

    /**
     * A single entry of the trail. The two hashes are exposed so a caller can re-link the chain
     * and show that no entry was removed or reordered.
     */
    public record AuditEventView(UUID id, UUID documentId, String eventType, String actorEmail,
                                  Map<String, Object> payload, LocalDateTime createdAt,
                                  String eventHash, String previousEventHash) {}

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            log.warn("SHA-256 not available", e);
            return "unavailable";
        }
    }

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
