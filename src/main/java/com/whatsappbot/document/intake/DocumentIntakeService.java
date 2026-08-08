package com.whatsappbot.document.intake;

import com.whatsappbot.document.DocumentEntity;
import com.whatsappbot.document.DocumentService;
import com.whatsappbot.document.IntakeChannel;
import com.whatsappbot.document.scan.MalwareScanProperties;
import com.whatsappbot.document.scan.MalwareScanService;
import com.whatsappbot.document.scan.ScanOutcome;
import com.whatsappbot.document.scan.ScanResult;
import com.whatsappbot.domain.tenant.TenantRepository;
import com.whatsappbot.storage.MediaAssetEntity;
import com.whatsappbot.storage.MediaAssetRepository;
import com.whatsappbot.storage.ObjectStorageService;
import com.whatsappbot.storage.StoredObjectRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The one place any externally-sourced file becomes a {@link DocumentEntity}.
 *
 * <p>Every intake channel — a shareable upload link today, WhatsApp document messages, email
 * attachments later — funnels through here: buffer to a bounded temp file, scan it, hand the
 * scanned bytes to the pluggable {@link ObjectStorageService}, and only then create the document.
 * A file that fails or cannot be scanned never reaches {@link DocumentService}. The temp file is
 * this service's only footprint on local disk, and it is always removed before returning —
 * whatever storage provider is configured is the only durable home for the bytes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties({DocumentIntakeProperties.class, MalwareScanProperties.class})
public class DocumentIntakeService {

    private final DocumentIntakeProperties properties;
    private final MalwareScanProperties scanProperties;
    private final MalwareScanService malwareScanService;
    private final ObjectStorageService objectStorageService;
    private final MediaAssetRepository mediaAssetRepository;
    private final TenantRepository tenantRepository;
    private final DocumentService documentService;

    public record IntakeRequest(UUID tenantId, IntakeChannel channel, String docType, UUID projectId,
                                String title, String description, String uploaderName,
                                String uploaderEmail, UUID uploadLinkId) {}

    public DocumentEntity ingest(IntakeRequest req, String originalFileName, String contentType,
                                 InputStream data) {
        Path temp = bufferToTempFile(data);
        try {
            long sizeBytes = temp.toFile().length();
            ScanResult scan = scan(temp);
            String scanStatus = resolveScanStatus(scan);

            StoredObjectRef ref;
            try (InputStream storeStream = Files.newInputStream(temp)) {
                ref = objectStorageService.putObject(req.tenantId(), originalFileName, contentType,
                        storeStream, sizeBytes);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read buffered upload for storage", e);
            }

            MediaAssetEntity asset = new MediaAssetEntity();
            asset.setTenant(tenantRepository.getReferenceById(req.tenantId()));
            asset.setOriginalName(originalFileName != null ? originalFileName : "upload");
            asset.setStoredPath(ref.objectKey());
            asset.setObjectKey(ref.objectKey());
            asset.setContentType(contentType != null ? contentType : "application/octet-stream");
            asset.setSizeBytes(ref.sizeBytes());
            asset.setAssetType("DOCUMENT");
            asset.setStorageProvider(objectStorageService.providerCode());
            asset.setChecksumSha256(ref.checksumSha256());
            asset.setScanStatus(scanStatus);
            asset.setScannedAt(scan.outcome() == ScanOutcome.UNAVAILABLE ? null : LocalDateTime.now());
            asset = mediaAssetRepository.save(asset);

            log.info("Document intake stored asset. tenant={} channel={} scanStatus={} objectKey={}",
                    req.tenantId(), req.channel(), scanStatus, ref.objectKey());

            return documentService.createDocumentFromIntake(req.tenantId(), req.channel(), req.docType(),
                    req.projectId(), req.title(), req.description(), req.uploaderName(),
                    req.uploaderEmail(), req.uploadLinkId(), asset);
        } finally {
            deleteQuietly(temp);
        }
    }

    private ScanResult scan(Path temp) {
        try (InputStream scanStream = Files.newInputStream(temp)) {
            return malwareScanService.scan(scanStream);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read buffered upload for scanning", e);
        }
    }

    /** CLEAN proceeds; INFECTED always rejects; UNAVAILABLE rejects unless failOpen is set. */
    private String resolveScanStatus(ScanResult scan) {
        return switch (scan.outcome()) {
            case CLEAN -> "CLEAN";
            case INFECTED -> throw new MalwareDetectedException(scan.signature());
            case UNAVAILABLE -> {
                if (!scanProperties.isFailOpen()) {
                    throw new ScannerUnavailableException(scan.detail());
                }
                log.warn("Malware scanner unavailable; accepting upload unscanned per failOpen config. detail={}",
                        scan.detail());
                yield "PENDING";
            }
        };
    }

    private Path bufferToTempFile(InputStream data) {
        long limit = properties.getMaxFileSizeBytes();
        try {
            Path temp = Files.createTempFile("doc-intake-", ".bin");
            long written = 0;
            byte[] buffer = new byte[8192];
            try (OutputStream out = Files.newOutputStream(temp)) {
                int read;
                while ((read = data.read(buffer)) > 0) {
                    written += read;
                    if (written > limit) {
                        Files.deleteIfExists(temp);
                        throw new FileTooLargeException(limit);
                    }
                    out.write(buffer, 0, read);
                }
            }
            return temp;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to buffer upload for scanning", e);
        }
    }

    private void deleteQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.warn("Could not delete intake temp file. path={}", temp, e);
        }
    }
}
