package com.whatsappbot.video.image;

import com.whatsappbot.video.VideoScriptEntity;
import com.whatsappbot.video.VideoScriptRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class StoryboardImageService {

    private final StoryboardImageJobRepository jobRepository;
    private final VideoScriptRepository videoScriptRepository;
    private final CharacterProfileService profileService;
    private final HybridImageProviderRouter providerRouter;
    private final MediaGenerationProperties properties;
    private final StoryboardImageWorker worker;
    private final TaskExecutor executor;

    public StoryboardImageService(
            StoryboardImageJobRepository jobRepository,
            VideoScriptRepository videoScriptRepository,
            CharacterProfileService profileService,
            HybridImageProviderRouter providerRouter,
            MediaGenerationProperties properties,
            StoryboardImageWorker worker,
            @Qualifier("storyboardImageExecutor") TaskExecutor executor) {
        this.jobRepository = jobRepository;
        this.videoScriptRepository = videoScriptRepository;
        this.profileService = profileService;
        this.providerRouter = providerRouter;
        this.properties = properties;
        this.worker = worker;
        this.executor = executor;
    }

    @Transactional
    public StoryboardImageJobEntity queue(
            UUID tenantId,
            UUID scriptId,
            UUID characterProfileId,
            int shotIndex,
            String prompt,
            StoryboardQualityMode qualityMode,
            BigDecimal maximumShotCostUsd,
            BigDecimal reelBudgetUsd) {
        if (!providerRouter.available()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "No image provider is configured. Enable the local worker or Gemini.");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image prompt is required");
        }
        if (shotIndex < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shot index cannot be negative");
        }

        VideoScriptEntity script = videoScriptRepository.findForUpdateByIdAndTenantId(scriptId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video script not found"));
        CharacterProfileEntity character = characterProfileId == null
                ? null
                : profileService.get(tenantId, characterProfileId);

        StoryboardQualityMode resolvedQuality = qualityMode == null
                ? StoryboardQualityMode.BALANCED
                : qualityMode;
        BigDecimal shotCap = positiveOrDefault(
                maximumShotCostUsd, properties.getDefaultShotBudgetUsd());
        BigDecimal reelCap = positiveOrDefault(
                reelBudgetUsd, properties.getDefaultReelBudgetUsd());
        if (shotCap.compareTo(properties.getHardMaximumShotBudgetUsd()) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Shot cap exceeds the server maximum $" + properties.getHardMaximumShotBudgetUsd());
        }
        if (reelCap.compareTo(properties.getHardMaximumReelBudgetUsd()) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Reel budget exceeds the server maximum $" + properties.getHardMaximumReelBudgetUsd());
        }
        BigDecimal estimate = providerRouter.reservedCost(resolvedQuality);
        BigDecimal alreadyReserved = jobRepository
                .findAllByTenantIdAndVideoScriptIdOrderByCreatedAtDesc(tenantId, scriptId)
                .stream()
                .map(job -> job.getStatus() == StoryboardImageStatus.COMPLETED
                        ? job.getActualCostUsd()
                        : job.getEstimatedCostUsd())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (estimate.compareTo(shotCap) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Estimated image cost $" + estimate + " exceeds the shot cap $" + shotCap);
        }
        if (alreadyReserved.add(estimate).compareTo(reelCap) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "This image would exceed the reel budget $" + reelCap);
        }

        StoryboardImageJobEntity job = new StoryboardImageJobEntity();
        job.setTenant(script.getTenant());
        job.setVideoScript(script);
        job.setCharacterProfile(character);
        job.setShotIndex(shotIndex);
        job.setPrompt(expandPrompt(prompt, character));
        job.setQualityMode(resolvedQuality);
        job.setEstimatedCostUsd(estimate);
        job.setStatus(StoryboardImageStatus.QUEUED);
        jobRepository.save(job);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.execute(() -> worker.generate(tenantId, job.getId(), shotCap));
            }
        });
        return job;
    }

    @Transactional(readOnly = true)
    public StoryboardImageJobEntity get(UUID tenantId, UUID jobId) {
        return jobRepository.findByIdAndTenantId(jobId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image job not found"));
    }

    @Transactional(readOnly = true)
    public List<StoryboardImageJobEntity> list(UUID tenantId, UUID scriptId) {
        if (videoScriptRepository.findByIdAndTenantId(scriptId, tenantId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video script not found");
        }
        return jobRepository.findAllByTenantIdAndVideoScriptIdOrderByCreatedAtDesc(tenantId, scriptId);
    }

    public ProviderStatus providerStatus() {
        return new ProviderStatus(
                providerRouter.available(),
                properties.getLocal().isEnabled(),
                properties.getGemini().isEnabled()
                        && properties.getGemini().getApiKey() != null
                        && !properties.getGemini().getApiKey().isBlank(),
                properties.getGemini().getFlashEstimatedCostUsd(),
                properties.getGemini().getProEstimatedCostUsd(),
                properties.getDefaultShotBudgetUsd(),
                properties.getDefaultReelBudgetUsd(),
                properties.getHardMaximumShotBudgetUsd(),
                properties.getHardMaximumReelBudgetUsd()
        );
    }

    private String expandPrompt(String prompt, CharacterProfileEntity character) {
        StringBuilder result = new StringBuilder(prompt.trim());
        result.append("\nVertical 9:16 cinematic storyboard frame. No captions, labels, logos, or generated UI text.");
        if (character != null) {
            result.append("\nMaintain the exact identity of the supplied character references.");
            if (character.getDescription() != null && !character.getDescription().isBlank()) {
                result.append("\nCharacter description: ").append(character.getDescription());
            }
            if (character.getVisualStyle() != null && !character.getVisualStyle().isBlank()) {
                result.append("\nVisual style: ").append(character.getVisualStyle());
            }
        }
        return result.toString();
    }

    private BigDecimal positiveOrDefault(BigDecimal value, BigDecimal fallback) {
        return value == null || value.signum() <= 0 ? fallback : value;
    }

    public record ProviderStatus(
            boolean available,
            boolean localEnabled,
            boolean geminiEnabled,
            BigDecimal flashEstimatedCostUsd,
            BigDecimal proEstimatedCostUsd,
            BigDecimal defaultShotBudgetUsd,
            BigDecimal defaultReelBudgetUsd,
            BigDecimal hardMaximumShotBudgetUsd,
            BigDecimal hardMaximumReelBudgetUsd
    ) {
    }
}
