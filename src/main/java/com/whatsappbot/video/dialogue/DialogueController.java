package com.whatsappbot.video.dialogue;

import com.whatsappbot.video.MediaRendererClient;
import com.whatsappbot.video.RendererProperties;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoint for dialogue video generation.
 * Thin controller: generates script via LLM, submits render job to renderer worker.
 * All processing happens in the renderer worker service.
 */
@RestController
@RequestMapping("/api/v1/video/dialogue")
@RequiredArgsConstructor
@Slf4j
public class DialogueController {

    private final DialogueScriptService scriptService;
    private final MediaRendererClient rendererClient;
    private final RendererProperties rendererProperties;

    /**
     * POST /api/v1/video/dialogue/script
     * Generate a dialogue script via LLM. Returns the script JSON for review.
     */
    @PostMapping("/script")
    public ResponseEntity<DialogueScript> generateScript(
            @AuthenticationPrincipal Claims claims,
            @RequestBody GenerateScriptRequest request
    ) {
        UUID tenantId = UUID.fromString(claims.get("tenantId", String.class));
        log.info("Generating dialogue script. tenant={} topic={}", tenantId, request.topic());
        DialogueScript script = scriptService.generate(request.topic(), request.maxTurns());
        return ResponseEntity.ok(script);
    }

    /**
     * POST /api/v1/video/dialogue/render
     * Submit a dialogue script to the renderer worker. Returns a job ID to poll.
     */
    @PostMapping("/render")
    public ResponseEntity<Map<String, Object>> submitRender(
            @AuthenticationPrincipal Claims claims,
            @RequestBody DialogueRenderRequest request
    ) {
        UUID tenantId = UUID.fromString(claims.get("tenantId", String.class));
        String jobId = UUID.randomUUID().toString();

        Path outputRoot = Path.of(rendererProperties.getOutputDir()).toAbsolutePath().normalize();
        Path output = outputRoot.resolve(tenantId.toString()).resolve(jobId + ".mp4").normalize();
        if (!output.startsWith(outputRoot)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid output path"));
        }

        log.info("Submitting dialogue render job. tenant={} job={} turns={}",
                tenantId, jobId, request.script().turns().size());

        try {
            rendererClient.submitDialogueRender(jobId, tenantId, request.script(), output.toString());
            return ResponseEntity.accepted().body(Map.of(
                    "jobId", jobId,
                    "status", "PENDING",
                    "message", "Render job submitted to worker service"
            ));
        } catch (Exception e) {
            log.error("Failed to submit dialogue render job. tenant={} error={}", tenantId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/v1/video/dialogue/render/{jobId}
     * Poll dialogue render status from the renderer worker.
     */
    @GetMapping("/render/{jobId}")
    public ResponseEntity<Map<String, Object>> renderStatus(
            @AuthenticationPrincipal Claims claims,
            @PathVariable String jobId
    ) {
        try {
            Map<String, Object> status = rendererClient.getDialogueRenderStatus(jobId);
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.warn("Failed to get dialogue render status. job={} error={}", jobId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/v1/video/character-pack/status
     * Proxy character pack status from the renderer worker.
     */
    @GetMapping("/character-pack/status")
    public ResponseEntity<Map<String, Object>> characterPackStatus(
            @AuthenticationPrincipal Claims claims
    ) {
        try {
            Map<String, Object> status = rendererClient.getCharacterPackStatus();
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.warn("Failed to get character pack status: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    public record GenerateScriptRequest(String topic, int maxTurns) {
        public GenerateScriptRequest {
            if (topic == null || topic.isBlank()) {
                throw new IllegalArgumentException("topic must not be blank");
            }
            if (maxTurns < 2) maxTurns = 6;
            if (maxTurns > 20) maxTurns = 20;
        }
    }

    public record DialogueRenderRequest(DialogueScript script) {}
}
