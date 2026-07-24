package com.whatsappbot.video.image;

import com.whatsappbot.storage.MediaAssetEntity;
import com.whatsappbot.storage.MediaAssetRepository;
import com.whatsappbot.storage.StorageService;
import com.whatsappbot.storage.StoredFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StoryboardImageWorker {

    private static final int MAX_REFERENCES = 5;
    private static final long MAX_REFERENCE_BYTES = 12 * 1024 * 1024;

    private final StoryboardImageJobStateService stateService;
    private final CharacterProfileService profileService;
    private final MediaAssetRepository mediaAssetRepository;
    private final StorageService storageService;
    private final HybridImageProviderRouter providerRouter;

    public void generate(UUID tenantId, UUID jobId, java.math.BigDecimal shotCostCap) {
        StoryboardImageJobEntity job = stateService.markGenerating(tenantId, jobId);
        if (job == null) {
            return;
        }

        try {
            List<ImageReference> references = loadReferences(tenantId, job);
            GeneratedImage generated = providerRouter.generate(
                    new ImageGenerationRequest(job.getPrompt(), job.getQualityMode(), references),
                    shotCostCap
            );

            String extension = generated.mimeType().contains("png") ? ".png" : ".jpg";
            String originalName = "storyboard-shot-" + job.getShotIndex() + extension;
            StoredFile stored = storageService.store(
                    tenantId,
                    originalName,
                    generated.mimeType(),
                    new ByteArrayInputStream(generated.data()),
                    generated.data().length
            );

            MediaAssetEntity asset = new MediaAssetEntity();
            asset.setTenant(job.getTenant());
            asset.setOriginalName(originalName);
            asset.setStoredPath(stored.storedPath());
            asset.setContentType(generated.mimeType());
            asset.setSizeBytes(stored.sizeBytes());
            asset.setAssetType("STORYBOARD_IMAGE");
            asset.setRefId(jobId);
            asset = mediaAssetRepository.save(asset);

            stateService.markCompleted(
                    tenantId, jobId, asset, generated.provider(), generated.actualCostUsd());
        } catch (Exception e) {
            log.error("Storyboard image job {} failed", jobId, e);
            stateService.markFailed(tenantId, jobId, shortMessage(e));
        }
    }

    private List<ImageReference> loadReferences(UUID tenantId, StoryboardImageJobEntity job) {
        if (job.getCharacterProfile() == null) {
            return List.of();
        }
        List<ImageReference> references = new ArrayList<>();
        for (MediaAssetEntity asset : profileService.references(
                tenantId, job.getCharacterProfile().getId())) {
            if (references.size() >= MAX_REFERENCES
                    || !asset.getContentType().startsWith("image/")
                    || asset.getSizeBytes() > MAX_REFERENCE_BYTES) {
                continue;
            }
            try (InputStream stream = storageService.retrieve(asset.getStoredPath())) {
                references.add(new ImageReference(stream.readAllBytes(), asset.getContentType()));
            } catch (Exception e) {
                log.warn("Skipping unreadable character reference asset {}", asset.getId());
            }
        }
        return references;
    }

    private String shortMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
