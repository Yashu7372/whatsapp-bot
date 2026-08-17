package com.whatsappbot.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.infrastructure.ai.AiProperties;
import com.whatsappbot.storage.MediaAssetEntity;
import com.whatsappbot.storage.MediaAssetRepository;
import com.whatsappbot.storage.StorageService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression coverage for the double-analyze race (two concurrent {@code analyze()} calls both
 * claiming the same document version) and for the FAILED-state persistence path that a prior
 * commit hardened without any test to verify it actually works.
 */
@ExtendWith(MockitoExtension.class)
class DocumentIntelligenceServiceTest {

    @Mock DocumentRepository documentRepository;
    @Mock DocumentVersionRepository versionRepository;
    @Mock MediaAssetRepository mediaAssetRepository;
    @Mock DocumentIntelligenceRepository intelligenceRepository;
    @Mock StorageService storageService;
    @Mock ChatModel chatModel;

    final UUID tenantId = UUID.randomUUID();
    final UUID userId = UUID.randomUUID();
    final UUID documentId = UUID.randomUUID();
    final UUID assetId = UUID.randomUUID();

    private DocumentIntelligenceService newService() {
        return new DocumentIntelligenceService(documentRepository, versionRepository, mediaAssetRepository,
                intelligenceRepository, storageService, chatModel, new AiProperties(), new ObjectMapper(),
                new DocumentIntelligenceProperties());
    }

    private DocumentEntity document() {
        DocumentEntity doc = new DocumentEntity();
        doc.setId(documentId);
        doc.setTitle("Shop drawing rev C");
        return doc;
    }

    private DocumentVersionEntity version(int num, long assetSizeUnused) {
        DocumentVersionEntity v = new DocumentVersionEntity();
        v.setDocumentId(documentId);
        v.setVersionNum(num);
        v.setAssetId(assetId);
        return v;
    }

    private MediaAssetEntity pdfAsset(long sizeBytes) {
        MediaAssetEntity asset = new MediaAssetEntity();
        asset.setId(assetId);
        asset.setContentType("application/pdf");
        asset.setSizeBytes(sizeBytes);
        asset.setStoredPath("tenants/" + tenantId + "/docs/rev-c.pdf");
        asset.setOriginalName("rev-c.pdf");
        return asset;
    }

    private void stubRegisterAndAsset(long assetSizeBytes) {
        when(documentRepository.findByIdAndTenantId(documentId, tenantId)).thenReturn(Optional.of(document()));
        when(versionRepository.findAllByDocumentIdAndTenant_IdOrderByVersionNumDesc(documentId, tenantId))
                .thenReturn(List.of(version(1, assetSizeBytes)));
        when(mediaAssetRepository.findByIdAndTenantId(assetId, tenantId)).thenReturn(Optional.of(pdfAsset(assetSizeBytes)));
    }

    private void stubPdfBytes() {
        when(storageService.retrieve(anyString())).thenReturn(new ByteArrayInputStream("%PDF-1.4".getBytes()));
    }

    private void stubClaimSucceeds() {
        when(intelligenceRepository.save(any(DocumentIntelligenceEntity.class))).thenAnswer(inv -> {
            DocumentIntelligenceEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
    }

    // ── Baseline behaviour ──────────────────────────────────────────────────

    @Test
    @DisplayName("returns the cached COMPLETED result without calling the model when not forced")
    void returnsCachedResultWhenNotForcedAndAlreadyCompleted() throws Exception {
        when(documentRepository.findByIdAndTenantId(documentId, tenantId)).thenReturn(Optional.of(document()));
        when(versionRepository.findAllByDocumentIdAndTenant_IdOrderByVersionNumDesc(documentId, tenantId))
                .thenReturn(List.of(version(1, 100)));

        DocumentIntelligenceEntity cached = new DocumentIntelligenceEntity();
        cached.setId(UUID.randomUUID());
        cached.setStatus("COMPLETED");
        cached.setResultJson("{\"summary\":\"cached\"}");
        when(intelligenceRepository.findByTenantIdAndDocumentIdAndVersionNum(tenantId, documentId, 1))
                .thenReturn(Optional.of(cached));

        DocumentIntelligenceService.AnalysisView result = newService().analyze(tenantId, userId, documentId, false);

        assertEquals("COMPLETED", result.status());
        verifyNoInteractions(chatModel);
        verify(intelligenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("force=true re-runs the analysis even though a COMPLETED cache entry exists")
    void forceTrueReRunsEvenWhenCompletedCacheExists() throws Exception {
        stubRegisterAndAsset(100);
        stubPdfBytes();
        stubClaimSucceeds();
        when(intelligenceRepository.findByTenantIdAndDocumentIdAndVersionNum(tenantId, documentId, 1))
                .thenReturn(Optional.empty());
        when(chatModel.chat(any(ChatMessage[].class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("{\"summary\":\"fresh\"}")).build());

        DocumentIntelligenceService.AnalysisView result = newService().analyze(tenantId, userId, documentId, true);

        assertEquals("COMPLETED", result.status());
        verify(chatModel).chat(any(ChatMessage[].class));
    }

    // ── Guards ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("rejects a non-PDF content type before ever claiming a run")
    void rejectsNonPdfContentType() {
        when(documentRepository.findByIdAndTenantId(documentId, tenantId)).thenReturn(Optional.of(document()));
        when(versionRepository.findAllByDocumentIdAndTenant_IdOrderByVersionNumDesc(documentId, tenantId))
                .thenReturn(List.of(version(1, 100)));
        when(intelligenceRepository.findByTenantIdAndDocumentIdAndVersionNum(tenantId, documentId, 1))
                .thenReturn(Optional.empty());
        MediaAssetEntity nonPdf = pdfAsset(100);
        nonPdf.setContentType("image/png");
        when(mediaAssetRepository.findByIdAndTenantId(assetId, tenantId)).thenReturn(Optional.of(nonPdf));

        assertThrows(IllegalArgumentException.class, () -> newService().analyze(tenantId, userId, documentId, false));
        verify(intelligenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("rejects a PDF larger than the configured inline-analysis limit")
    void rejectsFileLargerThanConfiguredLimit() {
        when(documentRepository.findByIdAndTenantId(documentId, tenantId)).thenReturn(Optional.of(document()));
        when(versionRepository.findAllByDocumentIdAndTenant_IdOrderByVersionNumDesc(documentId, tenantId))
                .thenReturn(List.of(version(1, 100)));
        when(intelligenceRepository.findByTenantIdAndDocumentIdAndVersionNum(tenantId, documentId, 1))
                .thenReturn(Optional.empty());
        when(mediaAssetRepository.findByIdAndTenantId(assetId, tenantId)).thenReturn(Optional.of(pdfAsset(999_999_999L)));

        assertThrows(IllegalArgumentException.class, () -> newService().analyze(tenantId, userId, documentId, false));
        verify(intelligenceRepository, never()).save(any());
    }

    // ── Failure-state persistence (the path a prior commit hardened, with no test until now) ──

    @Test
    @DisplayName("persists FAILED status and error message, then rethrows, when the provider call fails")
    void persistsFailedStatusAndRethrowsWhenProviderCallFails() {
        stubRegisterAndAsset(100);
        stubPdfBytes();
        stubClaimSucceeds();
        when(intelligenceRepository.findByTenantIdAndDocumentIdAndVersionNum(tenantId, documentId, 1))
                .thenReturn(Optional.empty());
        when(chatModel.chat(any(ChatMessage[].class))).thenThrow(new RuntimeException("provider unavailable"));

        assertThrows(RuntimeException.class, () -> newService().analyze(tenantId, userId, documentId, false));

        ArgumentCaptor<DocumentIntelligenceEntity> captor = ArgumentCaptor.forClass(DocumentIntelligenceEntity.class);
        verify(intelligenceRepository, times(2)).save(captor.capture());
        DocumentIntelligenceEntity finalState = captor.getAllValues().get(1);
        assertEquals("FAILED", finalState.getStatus());
        assertEquals("provider unavailable", finalState.getErrorMessage());
        assertNotNull(finalState.getCompletedAt());
    }

    // ── Concurrent-claim race (the bug this test class exists to pin down) ──

    @Test
    @DisplayName("a lost claim race returns the winner's COMPLETED result instead of an unhandled 500")
    void concurrentAnalyzeLoserReturnsWinnersCompletedResult() throws Exception {
        stubRegisterAndAsset(100);
        DocumentIntelligenceEntity winner = new DocumentIntelligenceEntity();
        winner.setId(UUID.randomUUID());
        winner.setStatus("COMPLETED");
        winner.setResultJson("{\"summary\":\"winner\"}");
        when(intelligenceRepository.findByTenantIdAndDocumentIdAndVersionNum(tenantId, documentId, 1))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(intelligenceRepository.save(any(DocumentIntelligenceEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uq_document_intelligence_version"));

        DocumentIntelligenceService.AnalysisView result = newService().analyze(tenantId, userId, documentId, true);

        assertEquals("COMPLETED", result.status());
        assertEquals(winner.getId(), result.id());
        verifyNoInteractions(chatModel);
    }

    @Test
    @DisplayName("a lost claim race surfaces a clear 409 when the winner is still PROCESSING")
    void concurrentAnalyzeLoserSurfacesConflictWhenWinnerStillProcessing() {
        stubRegisterAndAsset(100);
        DocumentIntelligenceEntity winner = new DocumentIntelligenceEntity();
        winner.setId(UUID.randomUUID());
        winner.setStatus("PROCESSING");
        when(intelligenceRepository.findByTenantIdAndDocumentIdAndVersionNum(tenantId, documentId, 1))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(intelligenceRepository.save(any(DocumentIntelligenceEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uq_document_intelligence_version"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> newService().analyze(tenantId, userId, documentId, true));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("already in progress"));
    }

    @Test
    @DisplayName("a lost claim race surfaces a clear 409 telling the caller to retry when the winner FAILED")
    void concurrentAnalyzeLoserSurfacesConflictWhenWinnerFailed() {
        stubRegisterAndAsset(100);
        DocumentIntelligenceEntity winner = new DocumentIntelligenceEntity();
        winner.setId(UUID.randomUUID());
        winner.setStatus("FAILED");
        winner.setErrorMessage("provider timeout");
        when(intelligenceRepository.findByTenantIdAndDocumentIdAndVersionNum(tenantId, documentId, 1))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(intelligenceRepository.save(any(DocumentIntelligenceEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uq_document_intelligence_version"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> newService().analyze(tenantId, userId, documentId, true));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("force=true"));
    }
}
