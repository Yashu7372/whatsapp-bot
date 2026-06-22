package com.whatsappbot.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(StorageProperties.class)
public class LocalFileStorageService implements StorageService {

    private final StorageProperties properties;

    @Override
    public StoredFile store(UUID tenantId, String originalName, String contentType,
                            InputStream data, long sizeBytes) {
        try {
            Path dir = resolveDir(tenantId);
            Files.createDirectories(dir);

            String ext = originalName.contains(".") ?
                    originalName.substring(originalName.lastIndexOf('.')) : "";
            String fileName = UUID.randomUUID() + ext;
            Path target = dir.resolve(fileName);

            long written = Files.copy(data, target);
            String relativePath = tenantId + "/" + LocalDate.now() + "/" + fileName;
            log.debug("Stored file. path={} bytes={}", target, written);
            return new StoredFile(relativePath, written);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store file: " + originalName, e);
        }
    }

    @Override
    public InputStream retrieve(String storedPath) {
        try {
            Path file = Paths.get(properties.getLocalDir()).resolve(storedPath);
            return Files.newInputStream(file);
        } catch (IOException e) {
            throw new UncheckedIOException("File not found: " + storedPath, e);
        }
    }

    @Override
    public void delete(String storedPath) {
        try {
            Path file = Paths.get(properties.getLocalDir()).resolve(storedPath);
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete file. path={} error={}", storedPath, e.getMessage());
        }
    }

    private Path resolveDir(UUID tenantId) {
        return Paths.get(properties.getLocalDir())
                .resolve(tenantId.toString())
                .resolve(LocalDate.now().toString());
    }
}
