package com.whatsappbot.application.knowledge;

import com.whatsappbot.domain.knowledge.KnowledgeDocument;
import com.whatsappbot.domain.knowledge.KnowledgeDocumentRepository;
import com.whatsappbot.infrastructure.ai.PgVectorKnowledgeStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class KnowledgeEmbeddingStartupRunner implements CommandLineRunner {

    private final KnowledgeDocumentRepository documentRepository;
    private final TextChunker textChunker;
    private final PgVectorKnowledgeStore vectorStore;

    @Value("${app.knowledge.rebuild-on-startup:false}")
    private boolean rebuildOnStartup;

    /**
     * Disabled by default. Enable locally when you seed knowledge through Flyway and want embeddings generated.
     */
    @Override
    @Transactional
    public void run(String... args) {
        if (!rebuildOnStartup) {
            return;
        }

        List<KnowledgeDocument> documents = documentRepository.findAll();
        for (KnowledgeDocument document : documents) {
            if (!document.isActive()) {
                continue;
            }
            vectorStore.replaceDocumentEmbeddings(
                    document.getTenant().getId(),
                    document.getId(),
                    textChunker.chunk(document.getContent()),
                    document.getMetadata()
            );
        }
        System.out.println("Knowledge embeddings rebuilt for active documents: " + documents.size());
    }
}
