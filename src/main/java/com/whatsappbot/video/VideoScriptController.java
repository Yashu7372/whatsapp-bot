package com.whatsappbot.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/video-scripts")
@RequiredArgsConstructor
public class VideoScriptController {

    private final VideoScriptService videoScriptService;
    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ResponseEntity<List<VideoScriptResponse>> list(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return ResponseEntity.ok(videoScriptService.listByTenant(tenantId).stream().map(this::toResponse).toList());
    }

    @PostMapping("/generate")
    public ResponseEntity<VideoScriptResponse> generate(@AuthenticationPrincipal Claims claims,
                                                         @RequestBody GenerateRequest request) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        if (request.topic() == null || request.topic().isBlank()) {
            throw new IllegalArgumentException("topic is required");
        }
        VideoScriptEntity script = videoScriptService.generate(
                tenant,
                request.topic().trim(),
                blankDefault(request.platformCode(), "INSTAGRAM"),
                blankDefault(request.contentType(), "REEL"),
                blankDefault(request.style(), "ENGAGING"),
                request.durationSecs() > 0 ? Math.min(request.durationSecs(), 90) : 30,
                request.contentIdeaId()
        );
        return ResponseEntity.ok(toResponse(script));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Claims claims, @PathVariable UUID id) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        videoScriptService.delete(tenantId, id);
        return ResponseEntity.noContent().build();
    }

    private VideoScriptResponse toResponse(VideoScriptEntity script) {
        return new VideoScriptResponse(
                script.getId(), script.getTitle(), script.getPlatformCode(), script.getContentType(),
                script.getStyle(), script.getDurationSecs(), script.getHook(), script.getScriptBody(),
                readJson(script.getShotList()), readJson(script.getHashtags()), script.getCaption(),
                script.getMusicSuggestion(), script.getStatus(), script.getGeneratedAt(), script.getCreatedAt()
        );
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value == null ? "[]" : value);
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }

    private String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    record GenerateRequest(String topic, String platformCode, String contentType,
                           String style, int durationSecs, UUID contentIdeaId) {}

    public record VideoScriptResponse(
            UUID id,
            String title,
            String platformCode,
            String contentType,
            String style,
            int durationSecs,
            String hook,
            String scriptBody,
            JsonNode shotList,
            JsonNode hashtags,
            String caption,
            String musicSuggestion,
            String status,
            LocalDateTime generatedAt,
            LocalDateTime createdAt
    ) {}
}
