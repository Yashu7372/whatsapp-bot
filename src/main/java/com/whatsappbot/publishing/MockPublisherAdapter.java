package com.whatsappbot.publishing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Slf4j
public class MockPublisherAdapter {

    public PublishResultDto publish(UUID tenantId, UUID jobId, String contentSnapshot) {
        String fakeId = "mock-" + jobId.toString().substring(0, 8);
        log.info("MockPublisher: simulated publish for job={} fakeId={}", jobId, fakeId);
        return new PublishResultDto(true, fakeId, null);
    }

    public record PublishResultDto(boolean success, String externalPublishId, String errorMessage) {}
}
