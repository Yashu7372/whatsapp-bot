package com.whatsappbot.video;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RenderJobProcessor {

    private final RenderJobService renderJobService;
    private final MediaRendererClient rendererClient;
    private final RendererProperties properties;

    @Scheduled(fixedDelayString = "${app.video.renderer.poll-interval-ms:2000}")
    public void processQueuedJobs() {
        if (!rendererClient.enabled()) {
            return;
        }
        List<RenderJobEntity> jobs = renderJobService.claimForProcessing(properties.getBatchSize());
        for (RenderJobEntity job : jobs) {
            try {
                RenderJobService.RenderContext context = renderJobService.loadContext(job.getId());
                MediaRendererClient.RenderResult result = rendererClient.render(context);
                renderJobService.markCompleted(job.getId(), result.outputPath());
            } catch (Exception e) {
                renderJobService.markFailed(job.getId(), e);
            }
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void recoverStuckJobs() {
        int reset = renderJobService.resetStuckProcessing();
        if (reset > 0) {
            log.warn("Reset {} stuck render jobs", reset);
        }
    }
}
