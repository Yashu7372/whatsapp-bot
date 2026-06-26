package com.whatsappbot.document;

import com.whatsappbot.auth.TenantUserRepository;
import com.whatsappbot.domain.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentAuditService {

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

        // Chain hash for tamper-evidence
        String previousHash = auditEventRepository
                .findLatestByDocumentId(documentId)
                .map(DocumentAuditEventEntity::getEventHash)
                .orElse("genesis");
        event.setPreviousEventHash(previousHash);
        event.setEventHash(sha256(tenantId.toString() + documentId + eventType + previousHash));

        return auditEventRepository.save(event);
    }

    @Transactional(readOnly = true)
    public List<DocumentAuditEventEntity> getAuditTrail(UUID tenantId, UUID documentId) {
        return auditEventRepository.findAllByDocumentIdOrderByCreatedAtAsc(documentId);
    }

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
