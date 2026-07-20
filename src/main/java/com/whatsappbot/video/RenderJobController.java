package com.whatsappbot.video;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/render-jobs")
@RequiredArgsConstructor
public class RenderJobController {

    private final RenderJobService service;
    private final TenantRepository tenantRepository;

    @PostMapping
    public ResponseEntity<RenderJobService.RenderJobResponse> create(
            @AuthenticationPrincipal Claims claims,
            @RequestBody RenderJobService.CreateRenderJob request) {
        UUID tenantId = tenantId(claims);
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        if (request.scriptId() == null || request.templateCode() == null || request.templateCode().isBlank()) {
            throw new IllegalArgumentException("scriptId and templateCode are required");
        }
        return ResponseEntity.accepted().body(service.create(tenant, request));
    }

    @GetMapping
    public ResponseEntity<List<RenderJobService.RenderJobResponse>> list(
            @AuthenticationPrincipal Claims claims) {
        return ResponseEntity.ok(service.list(tenantId(claims)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RenderJobService.RenderJobResponse> get(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {
        return ResponseEntity.ok(service.get(tenantId(claims), id));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<RenderJobService.RenderJobResponse> retry(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {
        return ResponseEntity.ok(service.retry(tenantId(claims), id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<RenderJobService.RenderJobResponse> cancel(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {
        return ResponseEntity.ok(service.cancel(tenantId(claims), id));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<FileSystemResource> download(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {
        Path output = service.outputFile(tenantId(claims), id);
        if (!Files.isRegularFile(output)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"reel-" + id + ".mp4\"")
                .contentType(MediaType.parseMediaType("video/mp4"))
                .contentLength(output.toFile().length())
                .body(new FileSystemResource(output));
    }

    private UUID tenantId(Claims claims) {
        return UUID.fromString((String) claims.get("tenantId"));
    }
}
