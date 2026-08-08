package com.whatsappbot.document.intake;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.TenantUserRepository;
import com.whatsappbot.document.DocumentEntity;
import com.whatsappbot.document.IntakeChannel;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Owns the full lifecycle of a shareable upload link: agent-side create/list/revoke, and the
 * public metadata -> password-verify -> upload sequence an unauthenticated recipient walks
 * through. Every public step is scoped strictly by the link's own token; nothing here ever
 * trusts a tenant, project or document id supplied by the caller.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(UploadLinkProperties.class)
public class UploadLinkService {

    private static final String EVENT_VIEWED = "VIEWED";
    private static final String EVENT_PASSWORD_FAILED = "PASSWORD_FAILED";
    private static final String EVENT_PASSWORD_OK = "PASSWORD_OK";
    private static final String EVENT_UPLOADED = "UPLOADED";
    private static final String EVENT_REJECTED = "REJECTED";
    private static final String EVENT_EXPIRED_ATTEMPT = "EXPIRED_ATTEMPT";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UploadLinkProperties properties;
    private final DocumentUploadLinkRepository linkRepository;
    private final DocumentUploadLinkEventRepository eventRepository;
    private final DocumentUploadLinkSessionRepository sessionRepository;
    private final TenantRepository tenantRepository;
    private final TenantUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final DocumentIntakeService documentIntakeService;

    public record CreateRequest(UUID projectId, String docType, String label, String password,
                                LocalDateTime expiresAt, Integer maxUploads) {}

    @Transactional
    public DocumentUploadLinkEntity create(UUID tenantId, UUID userId, CreateRequest req) {
        if (req.docType() == null || req.docType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "docType is required");
        }
        if (req.label() == null || req.label().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "label is required");
        }
        if (req.expiresAt() == null || !req.expiresAt().isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expiresAt is required and must be in the future");
        }
        if (req.maxUploads() != null && req.maxUploads() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "maxUploads must be at least 1");
        }

        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        TenantUserEntity user = userRepository.findById(userId).orElse(null);

        DocumentUploadLinkEntity link = new DocumentUploadLinkEntity();
        link.setTenant(tenant);
        link.setProjectId(req.projectId());
        link.setDocType(req.docType());
        link.setLabel(req.label());
        link.setToken(newToken());
        if (req.password() != null && !req.password().isBlank()) {
            link.setPasswordHash(passwordEncoder.encode(req.password()));
        }
        link.setMaxUploads(req.maxUploads());
        link.setExpiresAt(req.expiresAt());
        link.setCreatedBy(user);
        return linkRepository.save(link);
    }

    @Transactional(readOnly = true)
    public List<DocumentUploadLinkEntity> list(UUID tenantId) {
        return linkRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Transactional
    public void revoke(UUID tenantId, UUID id) {
        DocumentUploadLinkEntity link = linkRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload link not found"));
        link.setRevokedAt(LocalDateTime.now());
        linkRepository.save(link);
    }

    public record LinkMetadata(String label, boolean requiresPassword) {}

    @Transactional
    public LinkMetadata metadata(String token) {
        DocumentUploadLinkEntity link = requireUsableLink(token);
        recordEvent(link, EVENT_VIEWED, null, null, null, null);
        return new LinkMetadata(link.getLabel(), link.requiresPassword());
    }

    /** Password failures are throttled per link and client address, not globally for the link. */
    @Transactional
    public String startSession(String token, String password, String ipAddress) {
        DocumentUploadLinkEntity link = requireUsableLink(token);

        if (link.requiresPassword()) {
            LocalDateTime window = LocalDateTime.now().minusMinutes(properties.getPasswordLockoutMinutes());
            long recentFailures = eventRepository.countByLinkIdAndEventTypeAndIpAddressAndCreatedAtAfter(
                    link.getId(), EVENT_PASSWORD_FAILED, ipAddress, window);
            if (recentFailures >= properties.getMaxPasswordAttempts()) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many incorrect attempts from this client. Try again later.");
            }
            if (password == null || !passwordEncoder.matches(password, link.getPasswordHash())) {
                recordEvent(link, EVENT_PASSWORD_FAILED, null, null, ipAddress, null);
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect password");
            }
            recordEvent(link, EVENT_PASSWORD_OK, null, null, ipAddress, null);
        }

        DocumentUploadLinkSessionEntity session = new DocumentUploadLinkSessionEntity();
        session.setLinkId(link.getId());
        session.setToken(newToken());
        session.setExpiresAt(LocalDateTime.now().plusMinutes(properties.getDefaultSessionTtlMinutes()));
        sessionRepository.save(session);
        return session.getToken();
    }

    @Transactional
    public DocumentEntity upload(String sessionToken, String originalFileName, String contentType,
                                 InputStream data, String uploaderName, String uploaderEmail, String ipAddress) {
        DocumentUploadLinkSessionEntity session = sessionRepository.findByToken(sessionToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session is invalid or expired"));
        LocalDateTime now = LocalDateTime.now();
        if (session.isExpired() || sessionRepository.consumeValid(sessionToken, now) != 1) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session is invalid or expired");
        }

        DocumentUploadLinkEntity link = linkRepository.findById(session.getLinkId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload link not found"));

        // Reserve capacity before any expensive scan/storage work. This UPDATE is atomic, so two
        // concurrent requests cannot both consume the final slot. If ingestion fails, the enclosing
        // transaction rolls the reservation and one-use session consumption back together.
        if (linkRepository.tryReserveUploadSlot(link.getId()) != 1) {
            recordEvent(link, EVENT_EXPIRED_ATTEMPT, uploaderName, uploaderEmail, ipAddress, null);
            throw new ResponseStatusException(HttpStatus.GONE, "This upload link is no longer active");
        }

        try {
            DocumentIntakeService.IntakeRequest request = new DocumentIntakeService.IntakeRequest(
                    link.getTenant().getId(), IntakeChannel.LINK, link.getDocType(), link.getProjectId(),
                    originalFileName, null, uploaderName, uploaderEmail, link.getId());
            DocumentEntity doc = documentIntakeService.ingest(request, originalFileName, contentType, data);

            recordEvent(link, EVENT_UPLOADED, uploaderName, uploaderEmail, ipAddress, doc.getId());
            log.info("Document received via upload link. linkId={} documentId={}", link.getId(), doc.getId());
            return doc;
        } catch (RuntimeException e) {
            recordEvent(link, EVENT_REJECTED, uploaderName, uploaderEmail, ipAddress, null);
            throw e;
        }
    }

    private DocumentUploadLinkEntity requireUsableLink(String token) {
        DocumentUploadLinkEntity link = linkRepository.findByToken(token).orElse(null);
        if (link == null || !link.isUsable()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This upload link is not available");
        }
        return link;
    }

    private void recordEvent(DocumentUploadLinkEntity link, String eventType, String uploaderName,
                             String uploaderEmail, String ipAddress, UUID documentId) {
        DocumentUploadLinkEventEntity event = new DocumentUploadLinkEventEntity();
        event.setLinkId(link.getId());
        event.setTenant(link.getTenant());
        event.setEventType(eventType);
        event.setUploaderName(uploaderName);
        event.setUploaderEmail(uploaderEmail);
        event.setIpAddress(ipAddress);
        event.setDocumentId(documentId);
        eventRepository.save(event);
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
