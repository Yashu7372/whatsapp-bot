package com.whatsappbot.lead;

import com.whatsappbot.domain.tenant.TenantEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LeadSignalService {

    private final LeadSignalRepository leadSignalRepository;
    private final LeadScoringService leadScoringService;

    @Transactional
    public LeadSignalEntity extractFromInbound(TenantEntity tenant, UUID contactId, UUID conversationId,
                                               String messageText) {
        double score = leadScoringService.scoreInboundMessage(messageText);
        LeadIntentCategory intentCategory = leadScoringService.detectIntent(messageText);
        LeadSignalEntity signal = LeadSignalEntity.fromInbound(tenant, contactId, conversationId,
                messageText, intentCategory, score);
        LeadSignalEntity saved = leadSignalRepository.save(signal);
        log.debug("Lead signal extracted id={} tenant={} score={} intent={}", saved.getId(),
                tenant.getId(), score, intentCategory);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<LeadSignalEntity> listByTenant(UUID tenantId) {
        return leadSignalRepository.findAllByTenantIdOrderByScoreDesc(tenantId);
    }
}
