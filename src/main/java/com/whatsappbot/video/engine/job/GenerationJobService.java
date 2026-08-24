package com.whatsappbot.video.engine.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.video.engine.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GenerationJobService {

    private final GenerationJobRepository repository;
    private final VideoGenerationEngine engine;
    private final ObjectMapper objectMapper;

    @Transactional
    public GenerationJobResponse create(TenantEntity tenant, CreateGenerationJob command) {
        GenerationJobEntity job = new GenerationJobEntity();
        job.setTenant(tenant);
        job.setTopic(requireText(command.topic(), "topic"));
        job.setMode(command.mode() == null ? GenerationMode.FACELESS : command.mode());
        job.setPlatform(defaultText(command.platform(), "INSTAGRAM"));
        job.setTargetDurationSeconds(Math.max(5, Math.min(command.targetDurationSeconds(), 90)));
        job.setState(GenerationState.INTAKE);
        job.setStatus(GenerationJobStatus.READY);
        job.setOptions(write(command.options() == null ? Map.of() : command.options()));
        job.setArtifacts("[]");
        return toResponse(repository.save(job));
    }

    @Transactional(readOnly = true)
    public GenerationJobResponse get(UUID tenantId, UUID jobId) {
        return toResponse(requireJob(tenantId, jobId));
    }

    @Transactional
    public GenerationJobResponse advance(UUID tenantId, UUID jobId) {
        GenerationJobEntity job = requireJob(tenantId, jobId);
        if (job.getStatus() == GenerationJobStatus.COMPLETED) {
            return toResponse(job);
        }
        if (job.getStatus() == GenerationJobStatus.FAILED) {
            throw new IllegalStateException("Generation job is failed. Retry it before advancing.");
        }

        job.setStatus(GenerationJobStatus.RUNNING);
        job.setErrorMessage(null);
        job.setLastGateMessage(null);

        GenerationContext context = toContext(job);
        try {
            VideoGenerationEngine.ExecutionResult result = engine.executeNext(job.getState(), context);
            job.setState(result.state());
            job.setArtifacts(write(result.context().artifacts()));
            job.setLastGateMessage(result.gateResults().stream()
                    .map(GateResult::message)
                    .filter(message -> !message.isBlank())
                    .reduce((left, right) -> left + " | " + right)
                    .orElse(null));
            job.setStatus(result.state() == GenerationState.VERIFIED
                    ? GenerationJobStatus.COMPLETED
                    : GenerationJobStatus.READY);
        } catch (GateRejectedException rejected) {
            job.setArtifacts(write(rejected.rejectedContext().artifacts()));
            job.setStatus(GenerationJobStatus.BLOCKED);
            job.setLastGateMessage(rejected.result().code() + ": " + rejected.result().message());
        } catch (IllegalStateException blocked) {
            if (blocked.getMessage() != null && blocked.getMessage().startsWith("No generation adapter")) {
                job.setStatus(GenerationJobStatus.BLOCKED);
                job.setLastGateMessage(blocked.getMessage());
            } else {
                job.setStatus(GenerationJobStatus.FAILED);
                job.setErrorMessage(safeMessage(blocked));
            }
        } catch (RuntimeException error) {
            job.setStatus(GenerationJobStatus.FAILED);
            job.setErrorMessage(safeMessage(error));
        }

        return toResponse(job);
    }

    @Transactional
    public GenerationJobResponse retry(UUID tenantId, UUID jobId) {
        GenerationJobEntity job = requireJob(tenantId, jobId);
        if (job.getStatus() == GenerationJobStatus.COMPLETED) {
            throw new IllegalStateException("Completed generation jobs cannot be retried.");
        }
        job.setStatus(GenerationJobStatus.READY);
        job.setErrorMessage(null);
        job.setLastGateMessage(null);
        return toResponse(job);
    }

    private GenerationContext toContext(GenerationJobEntity job) {
        return new GenerationContext(
                job.getId(),
                job.getTenant().getId(),
                job.getTopic(),
                job.getMode(),
                job.getPlatform(),
                job.getTargetDurationSeconds(),
                readOptions(job.getOptions()),
                readArtifacts(job.getArtifacts())
        );
    }

    private GenerationJobEntity requireJob(UUID tenantId, UUID jobId) {
        return repository.findByIdAndTenantId(jobId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Generation job not found: " + jobId));
    }

    private GenerationJobResponse toResponse(GenerationJobEntity job) {
        return new GenerationJobResponse(
                job.getId(),
                job.getState(),
                job.getStatus(),
                job.getTopic(),
                job.getMode(),
                job.getPlatform(),
                job.getTargetDurationSeconds(),
                readOptions(job.getOptions()),
                readArtifacts(job.getArtifacts()),
                job.getLastGateMessage(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }

    private Map<String, String> readOptions(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json,
                    new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Invalid generation options JSON.", e);
        }
    }

    private List<GenerationArtifact> readArtifacts(String json) {
        try {
            return objectMapper.readValue(json == null ? "[]" : json,
                    new TypeReference<List<GenerationArtifact>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Invalid generation artifacts JSON.", e);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize generation job data.", e);
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    public record CreateGenerationJob(
            String topic,
            GenerationMode mode,
            String platform,
            int targetDurationSeconds,
            Map<String, String> options
    ) {}

    public record GenerationJobResponse(
            UUID id,
            GenerationState state,
            GenerationJobStatus status,
            String topic,
            GenerationMode mode,
            String platform,
            int targetDurationSeconds,
            Map<String, String> options,
            List<GenerationArtifact> artifacts,
            String lastGateMessage,
            String errorMessage,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}
}
