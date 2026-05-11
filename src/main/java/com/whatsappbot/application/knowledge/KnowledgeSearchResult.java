package com.whatsappbot.application.knowledge;

import java.util.UUID;

public record KnowledgeSearchResult(
        UUID documentId,
        int chunkIndex,
        String content,
        double distance
) {
}
