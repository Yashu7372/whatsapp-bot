package com.whatsappbot.video;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.storage.MediaAssetEntity;
import com.whatsappbot.storage.MediaAssetRepository;
import com.whatsappbot.storage.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RenderJobService {

    private static final List<String> ALLOWED_STOCK_HOST_SUFFIXES = List.of(
            "videos.pexels.com",
            "images.pexels.com",
            "cdn.pixabay.com"
    );

    private final RenderJobRepository repository;
    private final VideoScriptRepository scriptRepository;
    private final VideoTemplateService templateService;
    private final MediaAssetRepository mediaAssetRepository;
    private final StorageProperties storageProperties;
    private final RendererProperties rendererProperties;
    private final ObjectMapper objectMapper;

    @Transactional
    public RenderJobResponse create(TenantEntity tenant, CreateRenderJob request) {
        VideoScriptEntity script = scriptRepository.findById(request.scriptId())
                .filter(s -> s.getTenant().getId().equals(tenant.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Video script not found: " + request.scriptId()));
        templateService.requireTemplate(tenant.getId(), request.templateCode());

        List<UUID> assetIds = request.assetIds() == null ? List.of() : request.assetIds().stream().distinct().limit(8).toList();
        for (UUID assetId : assetIds) {
            mediaAssetRepository.findByIdAndTenantId(assetId, tenant.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Media asset not found: " + assetId));
        }
        List<String> assetUrls = request.assetUrls() == null ? List.of() : request.assetUrls().stream()
                .filter(url -> url != null && !url.isBlank())
                .distinct()
                .limit(8)
                .peek(this::validateExternalAssetUrl)
                .toList();

        RenderJobEntity job = new RenderJobEntity();
        job.setTenant(tenant);
        job.setScript(script);
        job.setTemplateCode(request.templateCode());
        job.setAssetIds(writeJson(assetIds));
        job.setAssetUrls(writeJson(assetUrls));
        job.setVoice(blankDefault(request.voice(), "af_heart"));
        job.setBrandName(trimToNull(request.brandName()));
        job.setCallToAction(trimToNull(request.callToAction()));
        job.setMaxRetries(rendererProperties.getMaxRetries());
        job.setStatus(RenderJobStatus.QUEUED);
        job.setProgress(0);
        job.setNextAttemptAt(LocalDateTime.now());
        return toResponse(repository.save(job));
    }

    @Transactional(readOnly = true)
    public List<RenderJobResponse> list(UUID tenantId) {
        return repository.findAllByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RenderJobResponse get(UUID tenantId, UUID jobId) {
        return toResponse(requireTenantJob(tenantId, jobId));
    }

    @Transactional
    public List<RenderJobEntity> claimForProcessing(int batchSize) {
        List<RenderJobEntity> jobs = repository.findQueuedSkipLocked(Math.max(1, batchSize));
        LocalDateTime now = LocalDateTime.now();
        jobs.forEach(job -> {
            job.setStatus(RenderJobStatus.PROCESSING);
            job.setProgress(10);
            job.setStartedAt(now);
            job.setErrorMessage(null);
        });
        repository.saveAll(jobs);
        return jobs;
    }

    @Transactional(readOnly = true)
    public RenderContext loadContext(UUID jobId) {
        RenderJobEntity job = repository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Render job not found: " + jobId));
        VideoScriptEntity script = job.getScript();
        List<UUID> ids = readUuidList(job.getAssetIds());
        List<String> assetPaths = new ArrayList<>();
        Path storageRoot = Path.of(storageProperties.getLocalDir()).toAbsolutePath().normalize();
        for (UUID assetId : ids) {
            MediaAssetEntity asset = mediaAssetRepository.findByIdAndTenantId(assetId, job.getTenant().getId())
                    .orElseThrow(() -> new IllegalArgumentException("Media asset no longer exists: " + assetId));
            Path resolved = storageRoot.resolve(asset.getStoredPath()).normalize();
            if (!resolved.startsWith(storageRoot)) {
                throw new IllegalArgumentException("Unsafe media asset path: " + asset.getStoredPath());
            }
            assetPaths.add(resolved.toString());
        }

        Path outputRoot = Path.of(rendererProperties.getOutputDir()).toAbsolutePath().normalize();
        Path output = outputRoot.resolve(job.getTenant().getId().toString())
                .resolve(job.getId() + ".mp4").normalize();
        if (!output.startsWith(outputRoot)) {
            throw new IllegalStateException("Unsafe render output path");
        }

        return new RenderContext(
                job.getId(),
                job.getTenant().getId(),
                job.getTemplateCode(),
                script.getTitle(),
                script.getHook(),
                script.getScriptBody(),
                parseJson(script.getShotList()),
                assetPaths,
                readStringList(job.getAssetUrls()),
                job.getVoice(),
                job.getBrandName(),
                job.getCallToAction(),
                Math.max(5, Math.min(script.getDurationSecs(), 90)),
                output.toString()
        );
    }

    @Transactional
    public void markCompleted(UUID jobId, String outputPath) {
        repository.findById(jobId).ifPresent(job -> {
            if (job.getStatus() != RenderJobStatus.PROCESSING) {
                log.warn("Ignoring late renderer completion for job={} status={}", jobId, job.getStatus());
                return;
            }
            job.setStatus(RenderJobStatus.COMPLETED);
            job.setProgress(100);
            job.setOutputPath(outputPath);
            job.setCompletedAt(LocalDateTime.now());
            job.setErrorMessage(null);
        });
    }

    @Transactional
    public void markFailed(UUID jobId, Exception error) {
        repository.findById(jobId).ifPresent(job -> {
            int retries = job.getRetryCount() + 1;
            job.setRetryCount(retries);
            job.setErrorMessage(safeError(error));
            if (retries >= job.getMaxRetries()) {
                job.setStatus(RenderJobStatus.FAILED);
                job.setProgress(0);
                job.setCompletedAt(LocalDateTime.now());
                log.error("Render job permanently failed. job={} retries={}", jobId, retries, error);
            } else {
                job.setStatus(RenderJobStatus.QUEUED);
                job.setProgress(0);
                job.setNextAttemptAt(LocalDateTime.now().plusSeconds((long) Math.pow(2, retries) * 5));
                log.warn("Render job will retry. job={} retry={} error={}", jobId, retries, safeError(error));
            }
        });
    }

    @Transactional
    public RenderJobResponse retry(UUID tenantId, UUID jobId) {
        RenderJobEntity job = requireTenantJob(tenantId, jobId);
        if (job.getStatus() == RenderJobStatus.PROCESSING) {
            throw new IllegalStateException("Cannot retry a job while it is processing");
        }
        job.setStatus(RenderJobStatus.QUEUED);
        job.setProgress(0);
        job.setRetryCount(0);
        job.setErrorMessage(null);
        job.setCompletedAt(null);
        job.setNextAttemptAt(LocalDateTime.now());
        return toResponse(job);
    }

    @Transactional
    public RenderJobResponse cancel(UUID tenantId, UUID jobId) {
        RenderJobEntity job = requireTenantJob(tenantId, jobId);
        if (job.getStatus() == RenderJobStatus.COMPLETED) {
            throw new IllegalStateException("Completed render jobs cannot be cancelled");
        }
        if (job.getStatus() == RenderJobStatus.PROCESSING) {
            throw new IllegalStateException("A processing render job cannot be cancelled safely");
        }
        job.setStatus(RenderJobStatus.CANCELLED);
        job.setProgress(0);
        job.setCompletedAt(LocalDateTime.now());
        return toResponse(job);
    }

    @Transactional
    public int resetStuckProcessing() {
        LocalDateTime now = LocalDateTime.now();
        return repository.resetStuckProcessing(
                now.minusMinutes(rendererProperties.getStuckRecoveryMinutes()), now);
    }

    @Transactional(readOnly = true)
    public Path outputFile(UUID tenantId, UUID jobId) {
        RenderJobEntity job = requireTenantJob(tenantId, jobId);
        if (job.getStatus() != RenderJobStatus.COMPLETED || job.getOutputPath() == null) {
            throw new IllegalStateException("Render output is not ready");
        }
        Path root = Path.of(rendererProperties.getOutputDir()).toAbsolutePath().normalize();
        Path file = Path.of(job.getOutputPath()).toAbsolutePath().normalize();
        if (!file.startsWith(root)) {
            throw new IllegalStateException("Stored render path is outside the configured output directory");
        }
        return file;
    }

    private RenderJobEntity requireTenantJob(UUID tenantId, UUID jobId) {
        return repository.findByIdAndTenantId(jobId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Render job not found: " + jobId));
    }

    private RenderJobResponse toResponse(RenderJobEntity job) {
        return new RenderJobResponse(
                job.getId(),
                job.getScript().getId(),
                job.getTemplateCode(),
                job.getStatus(),
                job.getProgress(),
                job.getVoice(),
                job.getBrandName(),
                job.getCallToAction(),
                job.getOutputPath() != null,
                job.getErrorMessage(),
                job.getRetryCount(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt()
        );
    }

    private void validateExternalAssetUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid stock media URL", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Stock media URL must use HTTPS");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = ALLOWED_STOCK_HOST_SUFFIXES.stream()
                .anyMatch(suffix -> host.equals(suffix) || host.endsWith("." + suffix));
        if (!allowed) {
            throw new IllegalArgumentException("Unsupported stock media host: " + host);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize render job data", e);
        }
    }

    private List<UUID> readUuidList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<UUID>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Invalid asset id list", e);
        }
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Invalid asset URL list", e);
        }
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json == null ? "[]" : json);
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }

    private String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String safeError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    public record CreateRenderJob(
            UUID scriptId,
            String templateCode,
            List<UUID> assetIds,
            List<String> assetUrls,
            String voice,
            String brandName,
            String callToAction
    ) {}

    public record RenderJobResponse(
            UUID id,
            UUID scriptId,
            String templateCode,
            RenderJobStatus status,
            int progress,
            String voice,
            String brandName,
            String callToAction,
            boolean outputReady,
            String errorMessage,
            int retryCount,
            LocalDateTime createdAt,
            LocalDateTime startedAt,
            LocalDateTime completedAt
    ) {}

    public record RenderContext(
            UUID jobId,
            UUID tenantId,
            String templateCode,
            String title,
            String hook,
            String scriptBody,
            JsonNode shotList,
            List<String> assetPaths,
            List<String> assetUrls,
            String voice,
            String brandName,
            String callToAction,
            int durationSeconds,
            String outputPath
    ) {}
}
