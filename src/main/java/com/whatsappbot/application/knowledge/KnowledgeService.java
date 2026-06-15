package com.whatsappbot.application.knowledge;

import com.whatsappbot.domain.knowledge.KnowledgeDocument;
import com.whatsappbot.domain.knowledge.KnowledgeDocumentRepository;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import com.whatsappbot.infrastructure.ai.PgVectorKnowledgeStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final TenantRepository tenantRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final TextChunker textChunker;
    private final PgVectorKnowledgeStore vectorStore;

    @Transactional
    public UUID ingest(String tenantCode, KnowledgeIngestRequest request) {
        TenantEntity tenant = findActiveTenant(tenantCode);

        KnowledgeDocument document = KnowledgeDocument.create(
                tenant,
                request.title(),
                request.documentType(),
                request.safeSourceType(),
                request.content(),
                request.metadata() == null || request.metadata().isBlank() ? "{}" : request.metadata()
        );

        KnowledgeDocument saved = documentRepository.saveAndFlush(document);
        List<String> chunks = textChunker.chunk(request.content());
        vectorStore.replaceDocumentEmbeddings(tenant.getId(), saved.getId(), chunks, saved.getMetadata());
        return saved.getId();
    }

    @Transactional(readOnly = true)
    public List<KnowledgeSearchResult> search(String tenantCode, String query, int limit) {
        TenantEntity tenant = findActiveTenant(tenantCode);
        return vectorStore.search(tenant.getId(), query, limit);
    }

    private TenantEntity findActiveTenant(String tenantCode) {
        return tenantRepository.findByTenantCodeAndActiveTrue(tenantCode)
                .orElseThrow(() -> new IllegalArgumentException("No active tenant configured for tenant_code=" + tenantCode));
    }

    @Transactional(readOnly = true)
    public String buildContext(UUID tenantId, String userMessage) {
        List<KnowledgeSearchResult> results = vectorStore.search(tenantId, userMessage, 5);
        if (results.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder("Relevant business knowledge:\n");
        for (int i = 0; i < results.size(); i++) {
            context.append("\n[").append(i + 1).append("] ")
                    .append(results.get(i).content());
        }
        return context.toString();
    }
}
