package com.whatsappbot.reels;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReelRenderJobProcessor {

    private final ReelRenderJobService reelRenderJobService;
    private final ReelRendererClient reelRendererClient;

    @Scheduled(fixedDelayString = "${app.reels.poll-interval-ms:2000}")
    public void processNext() {
        reelRenderJobService.claimNext().ifPresent(this::render);
    }

    private void render(UUID jobId) {
        try {
            ReelRendererClient.RenderPayload payload = reelRenderJobService.prepareRenderPayload(jobId);
            byte[] mp4 = reelRendererClient.render(payload);
            reelRenderJobService.complete(jobId, mp4);
            log.info("Reel render completed. jobId={} bytes={}", jobId, mp4.length);
        } catch (Exception error) {
            log.warn("Reel render failed. jobId={} error={}", jobId, error.getMessage(), error);
            reelRenderJobService.fail(jobId, error);
        }
    }
}
