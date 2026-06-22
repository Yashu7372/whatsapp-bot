package com.whatsappbot.publishing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PublishingScheduler {

    private final PublishJobRepository publishJobRepository;
    private final PublishingService publishingService;

    @Scheduled(fixedDelayString = "${app.publishing.scheduler.interval-ms:30000}")
    public void processScheduledJobs() {
        LocalDateTime now = LocalDateTime.now();
        List<PublishJobEntity> dueJobs = publishJobRepository.findAllByStatusAndScheduledAtBefore(
                PublishStatus.SCHEDULED, now);
        for (PublishJobEntity job : dueJobs) {
            try {
                publishingService.executeJob(job);
            } catch (Exception e) {
                log.error("Failed to execute publish job={}", job.getId(), e);
            }
        }
    }
}
