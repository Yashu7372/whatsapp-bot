package com.whatsappbot.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.storage.StorageProperties;
import com.whatsappbot.video.image.StoryboardImageJobEntity;
import com.whatsappbot.video.image.StoryboardImageJobRepository;
import com.whatsappbot.video.image.StoryboardImageStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;

@Service
public class VideoRenderService {

    private final VideoScriptRepository videoScriptRepository;
    private final VideoRenderJobRepository renderJobRepository;
    private final VideoRenderWorker renderWorker;
    private final StoryboardImageJobRepository storyboardImageJobRepository;
    private final ObjectMapper objectMapper;
    private final BlenderRenderProperties properties;
    private final StorageProperties storageProperties;

    private final TaskExecutor renderExecutor;

    public VideoRenderService(
            VideoScriptRepository videoScriptRepository,
            VideoRenderJobRepository renderJobRepository,
            VideoRenderWorker renderWorker,
            StoryboardImageJobRepository storyboardImageJobRepository,
            ObjectMapper objectMapper,
            BlenderRenderProperties properties,
            StorageProperties storageProperties,
            @Qualifier("blenderRenderExecutor") TaskExecutor renderExecutor) {
        this.videoScriptRepository = videoScriptRepository;
        this.renderJobRepository = renderJobRepository;
        this.renderWorker = renderWorker;
        this.storyboardImageJobRepository = storyboardImageJobRepository;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.storageProperties = storageProperties;
        this.renderExecutor = renderExecutor;
    }

    @Transactional
    public VideoRenderJobEntity queue(UUID tenantId, UUID scriptId, String requestedTemplate) {
        VideoScriptEntity script = videoScriptRepository.findByIdAndTenantId(scriptId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video script not found"));

        String templateCode = requestedTemplate == null || requestedTemplate.isBlank()
                ? script.getTemplateCode()
                : requestedTemplate;
        templateCode = normalizeTemplateCode(templateCode);

        VideoRenderJobEntity job = new VideoRenderJobEntity();
        job.setTenant(script.getTenant());
        job.setVideoScript(script);
        job.setTemplateCode(templateCode);
        job.setStatus(VideoRenderStatus.QUEUED);
        job.setScenePlan(buildScenePlan(script, templateCode));
        renderJobRepository.save(job);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                renderExecutor.execute(() -> renderWorker.render(job.getId(), tenantId));
            }
        });
        return job;
    }

    private String normalizeTemplateCode(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]{1,100}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Blender template code");
        }
        return normalized;
    }

    @Transactional(readOnly = true)
    public VideoRenderJobEntity get(UUID tenantId, UUID jobId) {
        return renderJobRepository.findByIdAndTenantId(jobId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Render job not found"));
    }

    @Transactional(readOnly = true)
    public List<VideoRenderJobEntity> listForScript(UUID tenantId, UUID scriptId) {
        if (videoScriptRepository.findByIdAndTenantId(scriptId, tenantId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video script not found");
        }
        return renderJobRepository.findAllByTenantIdAndVideoScriptIdOrderByCreatedAtDesc(tenantId, scriptId);
    }

    @Transactional(readOnly = true)
    public Resource video(UUID tenantId, UUID jobId) {
        VideoRenderJobEntity job = get(tenantId, jobId);
        if (job.getStatus() != VideoRenderStatus.COMPLETED || job.getOutputPath() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Video render is not complete");
        }

        Path root = properties.workDir().toAbsolutePath().normalize();
        Path output = Path.of(job.getOutputPath()).toAbsolutePath().normalize();
        if (!output.startsWith(root) || !Files.isRegularFile(output)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rendered video file not found");
        }
        try {
            return new UrlResource(output.toUri());
        } catch (MalformedURLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid rendered video path", e);
        }
    }

    private String buildScenePlan(VideoScriptEntity script, String templateCode) {
        try {
            JsonNode shots = objectMapper.readTree(script.getShotList());
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("version", 1);
            plan.put("scriptId", script.getId());
            plan.put("templateCode", templateCode);
            plan.put("title", script.getTitle());
            plan.put("durationSecs", script.getDurationSecs());
            plan.put("fps", 24);
            plan.put("width", 1080);
            plan.put("height", 1920);
            plan.put("shots", shots);
            plan.put("storyboardImages", storyboardImages(script));
            return objectMapper.writeValueAsString(plan);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid Blender shot plan", e);
        }
    }

    private Map<String, String> storyboardImages(VideoScriptEntity script) {
        Path storageRoot = Path.of(storageProperties.getLocalDir()).toAbsolutePath().normalize();
        Map<String, String> images = new LinkedHashMap<>();
        for (StoryboardImageJobEntity job : storyboardImageJobRepository
                .findAllByTenantIdAndVideoScriptIdOrderByCreatedAtDesc(
                        script.getTenant().getId(), script.getId())) {
            if (job.getStatus() != StoryboardImageStatus.COMPLETED
                    || job.getOutputAsset() == null
                    || images.containsKey(Integer.toString(job.getShotIndex()))) {
                continue;
            }
            Path image = storageRoot.resolve(job.getOutputAsset().getStoredPath()).normalize();
            if (image.startsWith(storageRoot) && Files.isRegularFile(image)) {
                images.put(Integer.toString(job.getShotIndex()), image.toString());
            }
        }
        return images;
    }
}
