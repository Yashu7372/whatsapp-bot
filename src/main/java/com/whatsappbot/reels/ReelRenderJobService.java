package com.whatsappbot.reels;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import com.whatsappbot.storage.MediaAssetEntity;
import com.whatsappbot.storage.MediaAssetRepository;
import com.whatsappbot.storage.StorageService;
import com.whatsappbot.storage.StoredFile;
import com.whatsappbot.video.VideoScriptEntity;
import com.whatsappbot.video.VideoScriptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReelRenderJobService {

    private static final Set<String> TEMPLATES = Set.of("DYNAMIC_BOLD", "MINIMAL_PRODUCT", "NEWS_CARD");

    private final ReelRenderJobRepository reelRenderJobRepository;
    private final VideoScriptRepository videoScriptRepository;
    private final TenantRepository tenantRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final ReelProperties properties;

    @Transactional
    public ReelJobResponse create(
            UUID tenantId,
            UUID videoScriptId,
            String templateCode,
            Boolean includeVoice,
            String voice,
            List<UUID> assetIds,
            List<String> assetUrls
    ) {
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        VideoScriptEntity script = videoScriptRepository.findById(videoScriptId)
                .filter(value -> value.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Video script not found: " + videoScriptId));

        ReelRenderJobEntity job = new ReelRenderJobEntity();
        job.setTenant(tenant);
        job.setVideoScript(script);
        job.setTemplateCode(normalizeTemplate(templateCode));
        job.setStatus(ReelRenderStatus.PENDING);
        job.setIncludeVoice(Boolean.TRUE.equals(includeVoice));
        job.setVoice(isBlank(voice) ? properties.getDefaultVoice() : voice.trim());
        job.setAssetIds(writeJson(limit(assetIds, 8)));
        job.setAssetUrls(writeJson(limitStrings(assetUrls, 8)));
        return toResponse(reelRenderJobRepository.save(job));
    }

    @Transactional(readOnly = true)
    public List<ReelJobResponse> list(UUID tenantId) {
        return reelRenderJobRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ReelJobResponse get(UUID tenantId, UUID id) {
        return toResponse(findOwned(tenantId, id));
    }

    @Transactional
    public ReelJobResponse retry(UUID tenantId, UUID id) {
        ReelRenderJobEntity job = findOwned(tenantId, id);
        if (job.getStatus() == ReelRenderStatus.PROCESSING) {
            throw new IllegalStateException("A processing render job cannot be retried");
        }
        job.setStatus(ReelRenderStatus.PENDING);
        job.setErrorMessage(null);
        job.setStartedAt(null);
        job.setCompletedAt(null);
        return toResponse(reelRenderJobRepository.save(job));
    }

    @Transactional
    public Optional<UUID> claimNext() {
        return reelRenderJobRepository.findFirstByStatusOrderByCreatedAtAsc(ReelRenderStatus.PENDING)
                .map(job -> {
                    job.setStatus(ReelRenderStatus.PROCESSING);
                    job.setStartedAt(LocalDateTime.now());
                    job.setCompletedAt(null);
                    job.setErrorMessage(null);
                    job.setAttempts(job.getAttempts() + 1);
                    reelRenderJobRepository.save(job);
                    return job.getId();
                });
    }

    @Transactional(readOnly = true)
    public ReelRendererClient.RenderPayload prepareRenderPayload(UUID jobId) {
        ReelRenderJobEntity job = reelRenderJobRepository.findWithVideoScriptById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Render job not found: " + jobId));
        VideoScriptEntity script = job.getVideoScript();
        JsonNode shots = readTree(script.getShotList());

        List<ReelRendererClient.RenderAsset> assets = new ArrayList<>();
        for (UUID assetId : readUuidList(job.getAssetIds())) {
            if (assets.size() >= 8) {
                break;
            }
            mediaAssetRepository.findByIdAndTenantId(assetId, job.getTenant().getId())
                    .ifPresent(asset -> assets.add(toRenderAsset(asset)));
        }
        for (String url : readStringList(job.getAssetUrls())) {
            if (assets.size() >= 8) {
                break;
            }
            if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                assets.add(new ReelRendererClient.RenderAsset(
                        fileNameFromUrl(url), contentTypeFromUrl(url), null, url));
            }
        }

        return new ReelRendererClient.RenderPayload(
                job.getId().toString(),
                job.getTemplateCode(),
                script.getTitle(),
                script.getHook(),
                script.getScriptBody(),
                script.getCaption(),
                Math.max(5, Math.min(script.getDurationSecs(), 90)),
                shots,
                job.isIncludeVoice(),
                job.getVoice(),
                assets
        );
    }

    @Transactional
    public void complete(UUID jobId, byte[] mp4) {
        ReelRenderJobEntity job = reelRenderJobRepository.findWithVideoScriptById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Render job not found: " + jobId));
        String filename = "reel-" + job.getId() + ".mp4";
        StoredFile stored = storageService.store(
                job.getTenant().getId(),
                filename,
                "video/mp4",
                new ByteArrayInputStream(mp4),
                mp4.length
        );

        job.setOutputStoredPath(stored.storedPath());
        job.setOutputSizeBytes(stored.sizeBytes());
        job.setStatus(ReelRenderStatus.COMPLETED);
        job.setErrorMessage(null);
        job.setCompletedAt(LocalDateTime.now());
        reelRenderJobRepository.save(job);

        MediaAssetEntity generated = new MediaAssetEntity();
        generated.setTenant(job.getTenant());
        generated.setOriginalName(filename);
        generated.setStoredPath(stored.storedPath());
        generated.setContentType("video/mp4");
        generated.setSizeBytes(stored.sizeBytes());
        generated.setAssetType("GENERATED_REEL");
        generated.setRefId(job.getId());
        mediaAssetRepository.save(generated);
    }

    @Transactional
    public void fail(UUID jobId, Throwable error) {
        reelRenderJobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(ReelRenderStatus.FAILED);
            job.setCompletedAt(LocalDateTime.now());
            String message = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
            job.setErrorMessage(message.length() > 4000 ? message.substring(0, 4000) : message);
            reelRenderJobRepository.save(job);
        });
    }

    @Transactional(readOnly = true)
    public DownloadPayload download(UUID tenantId, UUID id) {
        ReelRenderJobEntity job = findOwned(tenantId, id);
        if (job.getStatus() != ReelRenderStatus.COMPLETED || isBlank(job.getOutputStoredPath())) {
            throw new IllegalStateException("Reel is not ready for download");
        }
        InputStream stream = storageService.retrieve(job.getOutputStoredPath());
        return new DownloadPayload(
                new InputStreamResource(stream),
                "reel-" + job.getId() + ".mp4",
                job.getOutputSizeBytes() != null ? job.getOutputSizeBytes() : -1L
        );
    }

    private ReelRenderJobEntity findOwned(UUID tenantId, UUID id) {
        return reelRenderJobRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Reel render job not found: " + id));
    }

    private ReelRendererClient.RenderAsset toRenderAsset(MediaAssetEntity asset) {
        try (InputStream input = storageService.retrieve(asset.getStoredPath())) {
            byte[] bytes = input.readNBytes(properties.getMaxAssetBytes() + 1);
            if (bytes.length > properties.getMaxAssetBytes()) {
                throw new IllegalArgumentException("Media asset exceeds the local render limit: " + asset.getOriginalName());
            }
            return new ReelRendererClient.RenderAsset(
                    asset.getOriginalName(),
                    asset.getContentType(),
                    Base64.getEncoder().encodeToString(bytes),
                    null
            );
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read media asset: " + asset.getOriginalName(), e);
        }
    }

    private ReelJobResponse toResponse(ReelRenderJobEntity job) {
        boolean ready = job.getStatus() == ReelRenderStatus.COMPLETED && !isBlank(job.getOutputStoredPath());
        return new ReelJobResponse(
                job.getId(),
                job.getVideoScript().getId(),
                job.getVideoScript().getTitle(),
                job.getTemplateCode(),
                job.getStatus(),
                job.isIncludeVoice(),
                job.getVoice(),
                job.getOutputSizeBytes(),
                job.getErrorMessage(),
                job.getAttempts(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getCompletedAt(),
                ready ? "/api/v1/reels/" + job.getId() + "/download" : null
        );
    }

    private String normalizeTemplate(String value) {
        String candidate = isBlank(value)
                ? properties.getDefaultTemplate()
                : value.trim().toUpperCase(Locale.ROOT);
        return TEMPLATES.contains(candidate) ? candidate : properties.getDefaultTemplate();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value != null ? value : List.of());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid reel asset selection", e);
        }
    }

    private JsonNode readTree(String value) {
        try {
            if (isBlank(value)) {
                return objectMapper.createArrayNode();
            }
            return objectMapper.readTree(value);
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }

    private List<UUID> readUuidList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> readStringList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<UUID> limit(List<UUID> values, int max) {
        return values == null ? List.of() : values.stream().filter(value -> value != null).limit(max).toList();
    }

    private List<String> limitStrings(List<String> values, int max) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .limit(max)
                .toList();
    }

    private String fileNameFromUrl(String url) {
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : "stock-media";
        int query = name.indexOf('?');
        return query >= 0 ? name.substring(0, query) : name;
    }

    private String contentTypeFromUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".mp4") || lower.contains(".mov") || lower.contains(".webm")) {
            return "video/mp4";
        }
        if (lower.contains(".png")) {
            return "image/png";
        }
        if (lower.contains(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record DownloadPayload(InputStreamResource resource, String filename, long sizeBytes) {}
}
