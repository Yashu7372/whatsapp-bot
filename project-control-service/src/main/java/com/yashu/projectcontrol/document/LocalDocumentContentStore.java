package com.yashu.projectcontrol.document;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class LocalDocumentContentStore {

    private static final long MAX_PDF_BYTES = 20L * 1024L * 1024L;
    private static final String URI_PREFIX = "local-file:";

    private final Path root;

    public LocalDocumentContentStore(
            @Value("${projectcontrol.document-storage.root:./project-control-files}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public StoredContent storePdf(byte[] bytes, String originalFilename) {
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF file is required");
        }
        if (bytes.length > MAX_PDF_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF must be 20 MB or smaller");
        }
        if (bytes.length < 4 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F') {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded content is not a PDF");
        }
        String filename = UUID.randomUUID() + ".pdf";
        try {
            Files.createDirectories(root);
            Files.write(root.resolve(filename), bytes);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store PDF", ex);
        }
        return new StoredContent(
                URI_PREFIX + filename,
                sha256(bytes),
                originalFilename == null || originalFilename.isBlank() ? filename : originalFilename,
                "application/pdf",
                (long) bytes.length);
    }

    public byte[] read(String contentUri) {
        Path path = resolve(contentUri);
        try {
            return Files.readAllBytes(path);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stored PDF was not found", ex);
        }
    }

    public void deleteQuietly(String contentUri) {
        try {
            Files.deleteIfExists(resolve(contentUri));
        } catch (RuntimeException | IOException ignored) {
            // best-effort cleanup after a failed revision transaction
        }
    }

    private Path resolve(String contentUri) {
        if (contentUri == null || !contentUri.startsWith(URI_PREFIX)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Revision has no locally stored PDF");
        }
        String filename = contentUri.substring(URI_PREFIX.length());
        Path path = root.resolve(filename).normalize();
        if (!path.getParent().equals(root)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid stored content reference");
        }
        return path;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public record StoredContent(
            String contentUri,
            String sha256,
            String originalFilename,
            String mediaType,
            long sizeBytes) {}
}
