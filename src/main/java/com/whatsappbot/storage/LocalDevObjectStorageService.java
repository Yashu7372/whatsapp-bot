package com.whatsappbot.storage;

import com.whatsappbot.domain.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Local-dev implementation of ObjectStorageService.
 * Issues tokens for upload via the local /api/v1/storage/local-upload endpoint.
 * Not suitable for production — use S3/R2/Azure in prod.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "local", matchIfMissing = true)
@RequiredArgsConstructor
public class LocalDevObjectStorageService implements ObjectStorageService {

    private final UploadTokenRepository uploadTokenRepository;
    private final TenantRepository tenantRepository;
    private final StorageService storageService;

    @Value("${app.storage.local-base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${app.storage.upload-token-ttl-seconds:900}")
    private long uploadTokenTtlSeconds;

    @Override
    @Transactional
    public SignedUploadUrl createUploadUrl(UUID tenantId, String fileName,
                                           String contentType, long sizeBytes) {
        String token     = UUID.randomUUID().toString();
        String objectKey = tenantId + "/" + UUID.randomUUID() + "/" + sanitize(fileName);

        UploadTokenEntity entity = new UploadTokenEntity();
        entity.setTenant(tenantRepository.getReferenceById(tenantId));
        entity.setToken(token);
        entity.setObjectKey(objectKey);
        entity.setContentType(contentType);
        entity.setSizeBytes(sizeBytes);
        entity.setExpiresAt(LocalDateTime.now().plusSeconds(uploadTokenTtlSeconds));
        uploadTokenRepository.save(entity);

        String uploadUrl = baseUrl + "/api/v1/storage/local-upload/" + token;
        log.debug("Created local signed upload URL. token={} objectKey={}", token, objectKey);
        return new SignedUploadUrl(uploadUrl, token, objectKey, uploadTokenTtlSeconds);
    }

    @Override
    public StoredObjectRef putObject(UUID tenantId, String fileName, String contentType,
                                     InputStream data, long sizeBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream digestStream = new DigestInputStream(data, digest)) {
                StoredFile stored = storageService.store(tenantId, fileName, contentType, digestStream, sizeBytes);
                String checksum = HexFormat.of().formatHex(digest.digest());
                log.debug("Stored object (local provider). objectKey={} bytes={} checksum={}",
                        stored.storedPath(), stored.sizeBytes(), checksum);
                return new StoredObjectRef(stored.storedPath(), stored.sizeBytes(), checksum);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        } catch (UncheckedIOException | java.io.IOException e) {
            throw new IllegalStateException("Failed to store object: " + fileName, e);
        }
    }

    @Override
    public SignedDownloadUrl createDownloadUrl(UUID tenantId, String objectKey) {
        String downloadUrl = baseUrl + "/api/v1/storage/local-download/"
                + UUID.randomUUID() + "?key=" + objectKey;
        return new SignedDownloadUrl(downloadUrl, uploadTokenTtlSeconds);
    }

    @Override
    public void deleteObject(UUID tenantId, String objectKey) {
        log.info("LocalDevObjectStorageService.deleteObject tenantId={} objectKey={}", tenantId, objectKey);
    }

    @Override
    public String providerCode() {
        return "LOCAL";
    }

    private String sanitize(String name) {
        if (name == null) return "upload";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
