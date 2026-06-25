package com.whatsappbot.video;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/video-scripts")
@RequiredArgsConstructor
public class VideoScriptController {

    private final VideoScriptService videoScriptService;
    private final TenantRepository tenantRepository;

    @GetMapping
    public ResponseEntity<List<VideoScriptEntity>> list(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return ResponseEntity.ok(videoScriptService.listByTenant(tenantId));
    }

    @PostMapping("/generate")
    public ResponseEntity<VideoScriptEntity> generate(@AuthenticationPrincipal Claims claims,
                                                       @RequestBody GenerateRequest request) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        VideoScriptEntity script = videoScriptService.generate(
                tenant,
                request.topic(),
                request.platformCode() != null ? request.platformCode() : "INSTAGRAM",
                request.contentType() != null ? request.contentType() : "REEL",
                request.style() != null ? request.style() : "ENGAGING",
                request.durationSecs() > 0 ? request.durationSecs() : 30,
                request.contentIdeaId()
        );
        return ResponseEntity.ok(script);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Claims claims, @PathVariable UUID id) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        videoScriptService.delete(tenantId, id);
        return ResponseEntity.noContent().build();
    }

    record GenerateRequest(String topic, String platformCode, String contentType,
                           String style, int durationSecs, UUID contentIdeaId) {}
}
