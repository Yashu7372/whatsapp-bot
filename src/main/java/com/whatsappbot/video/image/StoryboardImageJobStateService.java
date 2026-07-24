package com.whatsappbot.video.image;

import com.whatsappbot.storage.MediaAssetEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StoryboardImageJobStateService {

    private final StoryboardImageJobRepository jobRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StoryboardImageJobEntity markGenerating(UUID tenantId, UUID jobId) {
        StoryboardImageJobEntity job = jobRepository.findByIdAndTenantId(jobId, tenantId).orElse(null);
        if (job == null || job.getStatus() != StoryboardImageStatus.QUEUED) {
            return null;
        }
        job.setStatus(StoryboardImageStatus.GENERATING);
        job.setStartedAt(LocalDateTime.now());
        return jobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(
            UUID tenantId,
            UUID jobId,
            MediaAssetEntity outputAsset,
            String provider,
            BigDecimal actualCostUsd) {
        jobRepository.findByIdAndTenantId(jobId, tenantId).ifPresent(job -> {
            job.setStatus(StoryboardImageStatus.COMPLETED);
            job.setOutputAsset(outputAsset);
            job.setProvider(provider);
            job.setActualCostUsd(actualCostUsd);
            job.setErrorMessage(null);
            job.setCompletedAt(LocalDateTime.now());
            jobRepository.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID tenantId, UUID jobId, String errorMessage) {
        jobRepository.findByIdAndTenantId(jobId, tenantId).ifPresent(job -> {
            job.setStatus(StoryboardImageStatus.FAILED);
            job.setErrorMessage(errorMessage);
            job.setCompletedAt(LocalDateTime.now());
            jobRepository.save(job);
        });
    }
}
