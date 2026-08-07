package com.whatsappbot.document;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.TenantUserRepository;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Covers the tamper-evidence properties of the audit chain.
 *
 * <p>The original hash was {@code sha256(tenantId + documentId + eventType + previousHash)},
 * which committed to none of the content. Two approvals of the same document produced the same
 * digest, and {@code event_payload} — the record of who approved and what they said — could be
 * rewritten without breaking the chain. These tests pin down the properties that make the chain
 * worth keeping.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentAuditServiceTest {

    @Mock private DocumentAuditEventRepository auditEventRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private TenantUserRepository userRepository;

    @InjectMocks private DocumentAuditService service;

    private UUID tenantId;
    private UUID documentId;
    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        documentId = UUID.randomUUID();

        tenant = new TenantEntity();
        tenant.setId(tenantId);

        when(tenantRepository.getReferenceById(tenantId)).thenReturn(tenant);
        when(auditEventRepository.findLatestByDocumentId(any(), any())).thenReturn(Optional.empty());
        when(auditEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private TenantUserEntity actor(String email) {
        TenantUserEntity u = new TenantUserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        return u;
    }

    private Map<String, Object> payload(String decision) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("decision", decision);
        return p;
    }

    @Test
    void sameEventTypeOnSameDocumentDoesNotCollide() {
        TenantUserEntity user = actor("engineer@consultant.example");

        String first = service.record(tenantId, documentId, user.getId(),
                DocumentAuditService.APPROVAL_APPROVED, payload("APPROVED")).getEventHash();
        String second = service.record(tenantId, documentId, user.getId(),
                DocumentAuditService.APPROVAL_APPROVED, payload("REJECTED")).getEventHash();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void payloadIsCoveredByTheHash() {
        TenantUserEntity user = actor("engineer@consultant.example");

        DocumentAuditEventEntity event = service.record(tenantId, documentId, user.getId(),
                DocumentAuditService.APPROVAL_APPROVED, payload("APPROVED"));
        String originalHash = event.getEventHash();

        // Simulate someone rewriting the stored record to claim a different decision.
        event.getEventPayload().put("decision", "REJECTED");

        DocumentAuditEventEntity rehashed = service.record(tenantId, documentId, user.getId(),
                DocumentAuditService.APPROVAL_APPROVED, event.getEventPayload());

        assertThat(rehashed.getEventHash()).isNotEqualTo(originalHash);
    }

    @Test
    void actorIsCoveredByTheHash() {
        TenantUserEntity honest = actor("engineer@consultant.example");
        TenantUserEntity other = actor("someone.else@contractor.example");

        Map<String, Object> shared = payload("APPROVED");

        String byHonest = service.record(tenantId, documentId, honest.getId(),
                DocumentAuditService.APPROVAL_APPROVED, shared).getEventHash();
        String byOther = service.record(tenantId, documentId, other.getId(),
                DocumentAuditService.APPROVAL_APPROVED, shared).getEventHash();

        assertThat(byHonest).isNotEqualTo(byOther);
    }

    @Test
    void everyEventCarriesATimestampUsedByTheHash() {
        TenantUserEntity user = actor("engineer@consultant.example");

        DocumentAuditEventEntity event = service.record(tenantId, documentId, user.getId(),
                DocumentAuditService.DOCUMENT_SUBMITTED, payload("SUBMITTED"));

        // @PrePersist would only set this at flush, i.e. after the hash was computed.
        assertThat(event.getCreatedAt()).isNotNull();
    }

    @Test
    void firstEventStartsTheChainAndLaterEventsLinkToIt() {
        TenantUserEntity user = actor("engineer@consultant.example");

        DocumentAuditEventEntity first = service.record(tenantId, documentId, user.getId(),
                DocumentAuditService.DOCUMENT_CREATED, payload("CREATED"));
        assertThat(first.getPreviousEventHash()).isEqualTo("genesis");

        when(auditEventRepository.findLatestByDocumentId(tenantId, documentId))
                .thenReturn(Optional.of(first));

        DocumentAuditEventEntity second = service.record(tenantId, documentId, user.getId(),
                DocumentAuditService.DOCUMENT_SUBMITTED, payload("SUBMITTED"));

        assertThat(second.getPreviousEventHash()).isEqualTo(first.getEventHash());
    }

    @Test
    void aNullPayloadStillProducesAHash() {
        TenantUserEntity user = actor("engineer@consultant.example");

        DocumentAuditEventEntity event = service.record(tenantId, documentId, user.getId(),
                DocumentAuditService.DOCUMENT_CREATED, null);

        assertThat(event.getEventHash()).isNotBlank();
    }

    @Test
    void theAuditTrailQueryIsScopedToTheTenant() {
        service.getAuditTrail(tenantId, documentId);

        // Reading by document id alone let one tenant read another's approval history,
        // since document ids are guessable and the trail exposes actor emails.
        org.mockito.Mockito.verify(auditEventRepository)
                .findAllByTenantIdAndDocumentIdOrderByCreatedAtAsc(tenantId, documentId);
    }
}
