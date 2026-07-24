package com.whatsappbot.video;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class VideoRenderController {

    private final VideoRenderService videoRenderService;

    @PostMapping("/video-scripts/{scriptId}/render")
    public ResponseEntity<VideoRenderJobResponse> render(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID scriptId,
            @RequestBody(required = false) RenderRequest request) {
        UUID tenantId = tenantId(claims);
        String templateCode = request == null ? null : request.templateCode();
        return ResponseEntity.accepted()
                .body(VideoRenderJobResponse.from(videoRenderService.queue(tenantId, scriptId, templateCode)));
    }

    @GetMapping("/video-scripts/{scriptId}/render-jobs")
    public ResponseEntity<List<VideoRenderJobResponse>> list(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID scriptId) {
        return ResponseEntity.ok(videoRenderService.listForScript(tenantId(claims), scriptId)
                .stream()
                .map(VideoRenderJobResponse::from)
                .toList());
    }

    @GetMapping("/video-render-jobs/{jobId}")
    public ResponseEntity<VideoRenderJobResponse> get(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID jobId) {
        return ResponseEntity.ok(VideoRenderJobResponse.from(videoRenderService.get(tenantId(claims), jobId)));
    }

    @GetMapping(value = "/video-render-jobs/{jobId}/video", produces = "video/mp4")
    public ResponseEntity<Resource> video(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID jobId) {
        Resource resource = videoRenderService.video(tenantId(claims), jobId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .cacheControl(CacheControl.noCache())
                .body(resource);
    }

    private UUID tenantId(Claims claims) {
        return UUID.fromString((String) claims.get("tenantId"));
    }

    record RenderRequest(String templateCode) {
    }

    record VideoRenderJobResponse(
            UUID id,
            UUID videoScriptId,
            String templateCode,
            VideoRenderStatus status,
            String errorMessage,
            LocalDateTime createdAt,
            LocalDateTime startedAt,
            LocalDateTime completedAt
    ) {
        static VideoRenderJobResponse from(VideoRenderJobEntity job) {
            return new VideoRenderJobResponse(
                    job.getId(),
                    job.getVideoScript().getId(),
                    job.getTemplateCode(),
                    job.getStatus(),
                    job.getErrorMessage(),
                    job.getCreatedAt(),
                    job.getStartedAt(),
                    job.getCompletedAt()
            );
        }
    }
}
