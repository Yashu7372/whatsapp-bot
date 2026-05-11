package com.whatsappbot.infrastructure.ai;

import com.whatsappbot.application.knowledge.KnowledgeSearchResult;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PgVectorKnowledgeStore {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;

    @Transactional
    public void replaceDocumentEmbeddings(UUID tenantId, UUID documentId, List<String> chunks, String metadataJson) {
        jdbcTemplate.update("delete from knowledge_embeddings where document_id = ?", documentId);

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            Embedding embedding = embeddingModel.embed(chunk).content();
            String vector = toPgVector(embedding.vector());

            jdbcTemplate.update("""
                    insert into knowledge_embeddings
                        (tenant_id, document_id, chunk_index, content, embedding, metadata)
                    values
                        (?, ?, ?, ?, cast(? as vector), cast(? as jsonb))
                    """,
                    tenantId,
                    documentId,
                    i,
                    chunk,
                    vector,
                    metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson
            );
        }
    }

    public List<KnowledgeSearchResult> search(UUID tenantId, String query, int limit) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        String queryVector = toPgVector(queryEmbedding.vector());

        return jdbcTemplate.query("""
                select document_id,
                       chunk_index,
                       content,
                       embedding <=> cast(? as vector) as distance
                from knowledge_embeddings
                where tenant_id = ?
                order by embedding <=> cast(? as vector)
                limit ?
                """,
                ps -> {
                    ps.setString(1, queryVector);
                    ps.setObject(2, tenantId);
                    ps.setString(3, queryVector);
                    ps.setInt(4, Math.max(1, Math.min(limit, 20)));
                },
                (rs, rowNum) -> new KnowledgeSearchResult(
                        rs.getObject("document_id", UUID.class),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        rs.getDouble("distance")
                )
        );
    }

    private String toPgVector(float[] values) {
        List<String> parts = new ArrayList<>(values.length);
        for (float value : values) {
            parts.add(Float.toString(value));
        }
        return "[" + String.join(",", parts) + "]";
    }
}
