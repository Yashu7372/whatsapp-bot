package com.whatsappbot.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.infrastructure.ai.AiProperties;
import com.whatsappbot.storage.MediaAssetEntity;
import com.whatsappbot.storage.MediaAssetRepository;
import com.whatsappbot.storage.StorageService;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentIntelligenceService {

    private static final String PROMPT = """
            You are the document-intelligence engine for an enterprise construction project-control system.
            Analyze the COMPLETE PDF using both its text and visual content: cover sheets, tables, stamps,
            signatures, photographs, drawings, dimensions, callouts, clouds, arrows and handwritten/typed markups.

            Return ONLY valid JSON. Do not wrap it in markdown and do not invent missing facts.
            Use null or [] when evidence is absent. Every important finding must include page/evidence and confidence.

            Required JSON shape:
            {
              "documentType": "SHOP_DRAWING|MATERIAL_SUBMITTAL|METHOD_STATEMENT|SPECIFICATION|ARCHITECTURAL_DRAWING|OTHER",
              "summary": "short project-control summary",
              "metadata": {
                "projectNumber": null, "submittalNumber": null, "revision": null,
                "discipline": null, "contractor": null, "subcontractor": null,
                "consultant": null, "location": null
              },
              "workflow": {
                "status": null, "approvalScope": null, "submissionDate": null,
                "returnDate": null, "reviewOutcome": null
              },
              "technicalFacts": [
                {"fact":"", "value":"", "unit":null, "page":1, "evidence":"", "confidence":0.0}
              ],
              "reviewComments": [
                {"comment":"", "page":1, "source":"consultant|client|markup|document", "confidence":0.0}
              ],
              "selectedOrApprovedItems": [
                {"item":"", "value":"", "approvalScope":null, "page":1, "confidence":0.0}
              ],
              "outstandingActions": [
                {"action":"", "reason":"", "page":1, "confidence":0.0}
              ],
              "risksAndCoordination": [
                {"observation":"", "requiresReferenceCheck":true, "page":1, "confidence":0.0}
              ],
              "drawingElements": [
                {"element":"", "details":"", "page":1, "confidence":0.0}
              ],
              "referenceDocumentsMentioned": [""],
              "limitations": [""]
            }

            Important rules:
            - Distinguish full technical approval from limited approval such as colour-only approval.
            - A consultant comment or markup is not evidence that the contractor has already complied with it.
            - If a drawing says 'as per approved sample/specification', record the dependency; do not assume compliance.
            - Extract dimensions/materials only when visibly supported.
            - Treat conflicting or unreadable evidence as a limitation or reference-check item.
            """;

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final DocumentIntelligenceRepository intelligenceRepository;
    private final StorageService storageService;
    private final ChatModel chatModel;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    @Value("${document.intelligence.max-inline-bytes:20971520}")
    private long maxInlineBytes;

    @Transactional
    public AnalysisView analyze(UUID tenantId, UUID userId, UUID documentId, boolean force) throws IOException {
        DocumentEntity document = documentRepository.findByIdAndTenantId(documentId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));

        DocumentVersionEntity version = latestVersion(documentId);
        if (!force) {
            var existing = intelligenceRepository.findByTenantIdAndDocumentIdAndVersionNum(
                    tenantId, documentId, version.getVersionNum());
            if (existing.isPresent() && "COMPLETED".equals(existing.get().getStatus())) {
                return toView(existing.get());
            }
        }

        UUID assetId = version.getAssetId();
        if (assetId == null) {
            throw new IllegalStateException("Current document version has no uploaded file to analyze");
        }
        MediaAssetEntity asset = mediaAssetRepository.findByIdAndTenantId(assetId, tenantId)
                .orElseThrow(() -> new IllegalStateException("Document file asset not found"));

        String contentType = asset.getContentType() == null ? "" : asset.getContentType().toLowerCase(Locale.ROOT);
        if (!contentType.contains("pdf")) {
            throw new IllegalArgumentException("Native document intelligence currently accepts PDF files; contentType=" + asset.getContentType());
        }
        if (asset.getSizeBytes() > maxInlineBytes) {
            throw new IllegalArgumentException("PDF is too large for inline multimodal analysis (" + asset.getSizeBytes()
                    + " bytes > " + maxInlineBytes + "). Configure Files API processing for large documents.");
        }

        DocumentIntelligenceEntity run = intelligenceRepository
                .findByTenantIdAndDocumentIdAndVersionNum(tenantId, documentId, version.getVersionNum())
                .orElseGet(DocumentIntelligenceEntity::new);
        run.setTenantId(tenantId);
        run.setDocumentId(documentId);
        run.setVersionNum(version.getVersionNum());
        run.setStatus("PROCESSING");
        run.setProvider(aiProperties.getProvider().name());
        run.setModelName(activeModelName());
        run.setAnalyzedBy(userId);
        run.setErrorMessage(null);
        run.setCompletedAt(null);
        run = intelligenceRepository.save(run);

        try {
            byte[] pdfBytes = readBounded(asset.getStoredPath(), maxInlineBytes);
            String encoded = Base64.getEncoder().encodeToString(pdfBytes);
            UserMessage message = UserMessage.from(
                    PdfFileContent.from(encoded, "application/pdf"),
                    TextContent.from(contextPrompt(document, version, asset))
            );

            String raw = chatModel.chat(message).aiMessage().text();
            String json = normalizeJson(raw);
            objectMapper.readTree(json); // reject non-JSON before it enters project knowledge.

            run.setResultJson(json);
            run.setStatus("COMPLETED");
            run.setCompletedAt(LocalDateTime.now());
            intelligenceRepository.save(run);
            return toView(run);
        } catch (RuntimeException | IOException ex) {
            run.setStatus("FAILED");
            run.setErrorMessage(trim(ex.getMessage(), 4000));
            run.setCompletedAt(LocalDateTime.now());
            intelligenceRepository.save(run);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public AnalysisView latest(UUID tenantId, UUID documentId) {
        return intelligenceRepository.findTopByTenantIdAndDocumentIdOrderByVersionNumDesc(tenantId, documentId)
                .map(this::toView)
                .orElseThrow(() -> new IllegalArgumentException("No document intelligence result found"));
    }

    private DocumentVersionEntity latestVersion(UUID documentId) {
        List<DocumentVersionEntity> versions = versionRepository.findAllByDocumentIdOrderByVersionNumDesc(documentId);
        if (versions.isEmpty()) throw new IllegalStateException("Document has no version to analyze");
        return versions.getFirst();
    }

    private byte[] readBounded(String storedPath, long limit) throws IOException {
        try (InputStream in = storageService.retrieve(storedPath)) {
            byte[] bytes = in.readNBytes(Math.toIntExact(limit + 1));
            if (bytes.length > limit) throw new IllegalArgumentException("PDF exceeds configured inline analysis limit");
            return bytes;
        }
    }

    private String contextPrompt(DocumentEntity document, DocumentVersionEntity version, MediaAssetEntity asset) {
        return PROMPT + "\nKnown register context (use as context, but trust PDF evidence for extracted findings):\n"
                + "documentId=" + document.getId() + "\n"
                + "registerTitle=" + nullSafe(document.getTitle()) + "\n"
                + "registerDocType=" + nullSafe(document.getDocType()) + "\n"
                + "registerRevision=" + nullSafe(version.getRevisionCode()) + "\n"
                + "fileName=" + nullSafe(asset.getOriginalName()) + "\n";
    }

    private String activeModelName() {
        return switch (aiProperties.getProvider()) {
            case GEMINI -> aiProperties.getGemini().getModelName();
            case OLLAMA -> aiProperties.getOllama().getModelName();
            case OPENAI -> aiProperties.getOpenai().getModelName();
            case ANTHROPIC -> aiProperties.getAnthropic().getModelName();
        };
    }

    private String normalizeJson(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalStateException("AI returned an empty document analysis");
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) text = text.substring(firstNewline + 1, lastFence).trim();
        }
        return text;
    }

    private AnalysisView toView(DocumentIntelligenceEntity entity) {
        JsonNode result = null;
        if (entity.getResultJson() != null) {
            try { result = objectMapper.readTree(entity.getResultJson()); }
            catch (IOException ignored) { /* Stored status/error still remain observable. */ }
        }
        return new AnalysisView(entity.getId(), entity.getDocumentId(), entity.getVersionNum(), entity.getStatus(),
                entity.getProvider(), entity.getModelName(), result, entity.getErrorMessage(), entity.getCreatedAt(), entity.getCompletedAt());
    }

    private static String nullSafe(String value) { return value == null ? "" : value; }
    private static String trim(String value, int max) { return value == null ? null : value.substring(0, Math.min(value.length(), max)); }

    public record AnalysisView(UUID id, UUID documentId, int versionNum, String status, String provider,
                               String modelName, JsonNode result, String errorMessage,
                               LocalDateTime createdAt, LocalDateTime completedAt) {}
}
