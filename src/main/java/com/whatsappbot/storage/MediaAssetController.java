package com.whatsappbot.storage;

import com.whatsappbot.auth.TenantUserRepository;
import com.whatsappbot.domain.tenant.TenantRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MediaAssetController {

    private final StorageService storageService;
    private final ObjectStorageService objectStorageService;
    private final MediaAssetRepository mediaAssetRepository;
    private final UploadTokenRepository uploadTokenRepository;
    private final TenantRepository tenantRepository;
    private final TenantUserRepository userRepository;

    // ── Legacy direct upload (local dev / non-confidential managed mode) ──

    @PostMapping("/media/upload")
    public ResponseEntity<AssetResponse> upload(
            @AuthenticationPrincipal Claims claims,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "assetType", defaultValue = "DOCUMENT") String assetType,
            @RequestParam(value = "refId", required = false) UUID refId) throws IOException {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        UUID userId   = UUID.fromString(claims.getSubject());

        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        var user = userRepository.findById(userId).orElse(null);

        StoredFile stored = storageService.store(
                tenantId, file.getOriginalFilename(),
                file.getContentType(), file.getInputStream(), file.getSize());

        MediaAssetEntity asset = new MediaAssetEntity();
        asset.setTenant(tenant);
        asset.setOriginalName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload");
        asset.setStoredPath(stored.storedPath());
        asset.setObjectKey(stored.storedPath());
        asset.setContentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        asset.setSizeBytes(stored.sizeBytes());
        asset.setAssetType(assetType);
        asset.setRefId(refId);
        asset.setUploadedBy(user);
        asset.setStorageProvider("LOCAL");
        asset = mediaAssetRepository.save(asset);

        log.info("Media uploaded directly. assetId={} tenant={}", asset.getId(), tenantId);
        return ResponseEntity.ok(toResponse(asset));
    }

    // ── Signed upload URL flow (production / object-storage mode) ─────────

    @PostMapping("/storage/upload-url")
    public ResponseEntity<SignedUploadUrl> requestUploadUrl(
            @AuthenticationPrincipal Claims claims,
            @RequestBody UploadUrlRequest req) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        SignedUploadUrl url = objectStorageService.createUploadUrl(
                tenantId, req.fileName(), req.contentType(), req.sizeBytes());
        return ResponseEntity.ok(url);
    }

    @PostMapping("/storage/confirm-upload")
    public ResponseEntity<AssetResponse> confirmUpload(
            @AuthenticationPrincipal Claims claims,
            @RequestBody ConfirmUploadRequest req) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        UUID userId   = UUID.fromString(claims.getSubject());

        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        var user = userRepository.findById(userId).orElse(null);

        MediaAssetEntity asset = new MediaAssetEntity();
        asset.setTenant(tenant);
        asset.setOriginalName(req.originalName());
        asset.setStoredPath(req.objectKey());
        asset.setObjectKey(req.objectKey());
        asset.setContentType(req.contentType());
        asset.setSizeBytes(req.sizeBytes());
        asset.setAssetType(req.assetType() != null ? req.assetType() : "DOCUMENT");
        asset.setStorageProvider(objectStorageService.providerCode());
        asset.setChecksumSha256(req.checksumSha256());
        asset.setUploadedBy(user);
        asset.setCreatedBy(user);
        asset = mediaAssetRepository.save(asset);

        log.info("Asset confirmed. assetId={} provider={} key={}", asset.getId(),
                asset.getStorageProvider(), asset.getObjectKey());
        return ResponseEntity.ok(toResponse(asset));
    }

    // ── Local-dev upload receiver (used when storage.provider=local) ──────

    @PostMapping("/storage/local-upload/{token}")
    public ResponseEntity<Void> localUploadReceive(
            @PathVariable String token,
            @RequestParam("file") MultipartFile file) throws IOException {

        UploadTokenEntity uploadToken = uploadTokenRepository.findByTokenAndUsedFalse(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid or expired upload token"));

        if (uploadToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Upload token expired");
        }

        UUID tenantId = uploadToken.getTenant().getId();
        storageService.store(tenantId, file.getOriginalFilename(),
                file.getContentType(), file.getInputStream(), file.getSize());

        uploadToken.setUsed(true);
        uploadTokenRepository.save(uploadToken);
        log.info("Local upload received. token={} tenant={}", token, tenantId);
        return ResponseEntity.noContent().build();
    }

    // ── Signed download URL ───────────────────────────────────────────────

    @GetMapping("/storage/download-url/{assetId}")
    public ResponseEntity<SignedDownloadUrl> getDownloadUrl(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID assetId) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        MediaAssetEntity asset = mediaAssetRepository.findByIdAndTenantId(assetId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));

        SignedDownloadUrl url = objectStorageService.createDownloadUrl(tenantId, asset.getObjectKey());
        return ResponseEntity.ok(url);
    }

    // ── Media list / download / delete ───────────────────────────────────

    @GetMapping("/media")
    public ResponseEntity<List<AssetResponse>> list(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return ResponseEntity.ok(
                mediaAssetRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId)
                        .stream().map(this::toResponse).toList());
    }

    @GetMapping("/media/{id}/download")
    public ResponseEntity<InputStreamResource> download(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        MediaAssetEntity asset = mediaAssetRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));

        var stream = storageService.retrieve(asset.getStoredPath());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + asset.getOriginalName() + "\"")
                .contentType(MediaType.parseMediaType(asset.getContentType()))
                .body(new InputStreamResource(stream));
    }

    @DeleteMapping("/media/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        MediaAssetEntity asset = mediaAssetRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));

        objectStorageService.deleteObject(tenantId, asset.getObjectKey());
        storageService.delete(asset.getStoredPath());
        mediaAssetRepository.delete(asset);
        return ResponseEntity.noContent().build();
    }

    private AssetResponse toResponse(MediaAssetEntity a) {
        return new AssetResponse(
                a.getId(), a.getOriginalName(), a.getContentType(),
                a.getSizeBytes(), a.getAssetType(), a.getRefId(),
                a.getStorageProvider(), a.getObjectKey(),
                a.getStatus(), a.getCreatedAt());
    }

    // ── Records ──────────────────────────────────────────────────────────

    public record UploadUrlRequest(String fileName, String contentType, long sizeBytes) {}

    public record ConfirmUploadRequest(
            String originalName, String contentType, long sizeBytes,
            String objectKey, String assetType, String checksumSha256) {}

    public record AssetResponse(
            UUID id, String originalName, String contentType,
            long sizeBytes, String assetType, UUID refId,
            String storageProvider, String objectKey,
            String status, java.time.LocalDateTime createdAt) {}
}
