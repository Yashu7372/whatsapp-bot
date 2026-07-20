package com.whatsappbot.reels;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reels")
@RequiredArgsConstructor
public class ReelController {

    private static final MediaType VIDEO_MP4 = MediaType.parseMediaType("video/mp4");

    private final ReelRenderJobService reelRenderJobService;

    @GetMapping("/templates")
    public List<TemplateResponse> templates() {
        return List.of(
                new TemplateResponse(
                        "DYNAMIC_BOLD",
                        "Dynamic Bold",
                        "Dark gradient cards, large hook typography and fast social pacing",
                        "Best for tips, offers and attention-grabbing reels"
                ),
                new TemplateResponse(
                        "MINIMAL_PRODUCT",
                        "Minimal Product",
                        "Bright product-card layout with clean typography and media focus",
                        "Best for catalogues, services and product launches"
                ),
                new TemplateResponse(
                        "NEWS_CARD",
                        "News Card",
                        "Headline-led layout with a strong update or announcement treatment",
                        "Best for trends, industry news and explainers"
                )
        );
    }

    @PostMapping
    public ResponseEntity<ReelJobResponse> create(
            @AuthenticationPrincipal Claims claims,
            @RequestBody CreateReelRequest request
    ) {
        UUID tenantId = tenantId(claims);
        ReelJobResponse response = reelRenderJobService.create(
                tenantId,
                request.videoScriptId(),
                request.templateCode(),
                request.includeVoice(),
                request.voice(),
                request.assetIds(),
                request.assetUrls()
        );
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping
    public List<ReelJobResponse> list(@AuthenticationPrincipal Claims claims) {
        return reelRenderJobService.list(tenantId(claims));
    }

    @GetMapping("/{id}")
    public ReelJobResponse get(@AuthenticationPrincipal Claims claims, @PathVariable UUID id) {
        return reelRenderJobService.get(tenantId(claims), id);
    }

    @PostMapping("/{id}/retry")
    public ReelJobResponse retry(@AuthenticationPrincipal Claims claims, @PathVariable UUID id) {
        return reelRenderJobService.retry(tenantId(claims), id);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id
    ) {
        ReelRenderJobService.DownloadPayload payload = reelRenderJobService.download(tenantId(claims), id);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(VIDEO_MP4)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + payload.filename() + "\"");
        if (payload.sizeBytes() >= 0) {
            response.contentLength(payload.sizeBytes());
        }
        return response.body(payload.resource());
    }

    private UUID tenantId(Claims claims) {
        return UUID.fromString((String) claims.get("tenantId"));
    }

    public record CreateReelRequest(
            UUID videoScriptId,
            String templateCode,
            Boolean includeVoice,
            String voice,
            List<UUID> assetIds,
            List<String> assetUrls
    ) {}

    public record TemplateResponse(
            String code,
            String name,
            String description,
            String bestFor
    ) {}
}
