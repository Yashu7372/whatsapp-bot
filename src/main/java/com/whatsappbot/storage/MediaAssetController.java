package com.whatsappbot.storage;

import com.whatsappbot.auth.TenantUserRepository;
import com.whatsappbot.domain.tenant.TenantRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/media")
@RequiredArgsConstructor
public class MediaAssetController {

    private final StorageService storageService;
    private final MediaAssetRepository mediaAssetRepository;
    private final TenantRepository tenantRepository;
    private final TenantUserRepository userRepository;

    @PostMapping("/upload")
    public ResponseEntity<AssetResponse> upload(
            @AuthenticationPrincipal Claims claims,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "assetType", defaultValue = "DOCUMENT") String assetType,
            @RequestParam(value = "refId", required = false) UUID refId) throws IOException {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        UUID userId = UUID.fromString(claims.getSubject());

        if ("CHARACTER_REFERENCE".equals(assetType)) {
            if (refId == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Character reference uploads require refId");
            }
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Character reference must be an image");
            }
            if (file.getSize() > 12L * 1024 * 1024) {
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE, "Character reference must be 12 MB or smaller");
            }
        }

        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        var user = userRepository.findById(userId).orElse(null);

        StoredFile stored = storageService.store(
                tenantId, file.getOriginalFilename(),
                file.getContentType(), file.getInputStream(), file.getSize()
        );

        MediaAssetEntity asset = new MediaAssetEntity();
        asset.setTenant(tenant);
        asset.setOriginalName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload");
        asset.setStoredPath(stored.storedPath());
        asset.setContentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        asset.setSizeBytes(stored.sizeBytes());
        asset.setAssetType(assetType);
        asset.setRefId(refId);
        asset.setUploadedBy(user);
        asset = mediaAssetRepository.save(asset);

        log.info("Media uploaded. assetId={} tenant={}", asset.getId(), tenantId);
        return ResponseEntity.ok(toResponse(asset));
    }

    @GetMapping
    public ResponseEntity<List<AssetResponse>> list(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        List<AssetResponse> assets = mediaAssetRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream().map(this::toResponse).toList();
        return ResponseEntity.ok(assets);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        MediaAssetEntity asset = mediaAssetRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + id));

        var stream = storageService.retrieve(asset.getStoredPath());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + asset.getOriginalName() + "\"")
                .contentType(MediaType.parseMediaType(asset.getContentType()))
                .body(new InputStreamResource(stream));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        MediaAssetEntity asset = mediaAssetRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + id));

        storageService.delete(asset.getStoredPath());
        mediaAssetRepository.delete(asset);
        return ResponseEntity.noContent().build();
    }

    private AssetResponse toResponse(MediaAssetEntity a) {
        return new AssetResponse(
                a.getId(), a.getOriginalName(), a.getContentType(),
                a.getSizeBytes(), a.getAssetType(), a.getRefId(), a.getCreatedAt()
        );
    }

    public record AssetResponse(
            UUID id, String originalName, String contentType,
            long sizeBytes, String assetType, UUID refId,
            java.time.LocalDateTime createdAt) {}
}
