package com.whatsappbot.application.knowledge;

import com.whatsappbot.domain.knowledge.DocumentType;
import com.whatsappbot.domain.knowledge.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record KnowledgeIngestRequest(
        @NotBlank String title,
        @NotNull DocumentType documentType,
        SourceType sourceType,
        @NotBlank String content,
        String metadata
) {
    public SourceType safeSourceType() {
        return sourceType == null ? SourceType.ADMIN : sourceType;
    }
}
