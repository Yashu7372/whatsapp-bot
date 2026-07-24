package com.whatsappbot.video;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VideoRenderJobStateService {

    private final VideoRenderJobRepository renderJobRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VideoRenderJobEntity markRendering(UUID tenantId, UUID jobId) {
        VideoRenderJobEntity job = renderJobRepository.findByIdAndTenantId(jobId, tenantId).orElse(null);
        if (job == null || job.getStatus() != VideoRenderStatus.QUEUED) {
            return null;
        }
        job.setStatus(VideoRenderStatus.RENDERING);
        job.setStartedAt(LocalDateTime.now());
        return renderJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(UUID tenantId, UUID jobId, String outputPath, String logPath) {
        renderJobRepository.findByIdAndTenantId(jobId, tenantId).ifPresent(job -> {
            job.setStatus(VideoRenderStatus.COMPLETED);
            job.setOutputPath(outputPath);
            job.setLogPath(logPath);
            job.setCompletedAt(LocalDateTime.now());
            job.setErrorMessage(null);
            renderJobRepository.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID tenantId, UUID jobId, String logPath, String errorMessage) {
        renderJobRepository.findByIdAndTenantId(jobId, tenantId).ifPresent(job -> {
            job.setStatus(VideoRenderStatus.FAILED);
            job.setLogPath(logPath);
            job.setErrorMessage(errorMessage);
            job.setCompletedAt(LocalDateTime.now());
            renderJobRepository.save(job);
        });
    }
}
