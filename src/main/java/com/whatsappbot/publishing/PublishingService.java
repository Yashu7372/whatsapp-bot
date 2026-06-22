package com.whatsappbot.publishing;

import com.whatsappbot.domain.tenant.TenantEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PublishingService {

    private final PublishJobRepository publishJobRepository;
    private final PublishResultRepository publishResultRepository;
    private final MockPublisherAdapter mockPublisherAdapter;

    @Transactional
    public PublishJobEntity scheduleJob(TenantEntity tenant, UUID contentIdeaId, UUID platformAccountId,
                                        String platformCode, LocalDateTime scheduledAt, String contentSnapshot) {
        PublishJobEntity job = PublishJobEntity.create(tenant, contentIdeaId, platformAccountId,
                platformCode, scheduledAt, contentSnapshot);
        PublishJobEntity saved = publishJobRepository.save(job);
        log.info("Scheduled publish job id={} tenant={} platformCode={}", saved.getId(), tenant.getId(), platformCode);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<PublishJobEntity> listJobs(UUID tenantId) {
        return publishJobRepository.findAllByTenantIdOrderByScheduledAtDesc(tenantId);
    }

    @Transactional
    public PublishJobEntity executeJob(PublishJobEntity job) {
        job.setStatus(PublishStatus.RUNNING);
        publishJobRepository.save(job);

        try {
            MockPublisherAdapter.PublishResultDto result = mockPublisherAdapter.publish(
                    job.getTenant().getId(), job.getId(), job.getContentSnapshot());

            PublishResultEntity publishResult;
            if (result.success()) {
                publishResult = PublishResultEntity.ok(job.getTenant(), job.getId(), result.externalPublishId());
                job.setStatus(PublishStatus.PUBLISHED);
            } else {
                publishResult = PublishResultEntity.failed(job.getTenant(), job.getId(), result.errorMessage());
                job.setStatus(PublishStatus.FAILED);
            }
            publishResultRepository.save(publishResult);
        } catch (Exception e) {
            log.error("Publish job execution failed job={}", job.getId(), e);
            PublishResultEntity failResult = PublishResultEntity.failed(job.getTenant(), job.getId(), e.getMessage());
            publishResultRepository.save(failResult);
            job.setStatus(PublishStatus.FAILED);
        }

        return publishJobRepository.save(job);
    }
}
