package com.whatsappbot.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Validates zero-knowledge upload metadata before object storage is touched.
 *
 * <p>The database transaction cannot roll back a blob that has already been written to object
 * storage. The document service therefore must not discover deterministic request errors such as
 * a missing IV or ciphertext hash after the file has been persisted. This validator runs at the
 * API boundary before {@code StorageService.store(...)} can be reached.
 */
@Component
@RequiredArgsConstructor
public class EncryptedDocumentMetadataValidator {

    private final ObjectMapper objectMapper;

    public void validate(String metadataJson, MultipartFile encryptedFile) {
        if (metadataJson == null || metadataJson.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metadata is required");
        }

        final Map<String, Object> metadata;
        try {
            metadata = objectMapper.readValue(metadataJson, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "metadata must be valid JSON");
        }

        Object algorithm = metadata.get("encryptionAlg");
        if (algorithm != null && (!(algorithm instanceof String value) || value.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "encryptionAlg must be a non-blank string");
        }

        // These fields are required only when ciphertext is actually supplied, preserving the
        // existing metadata-only document behaviour while preventing orphaned encrypted files.
        if (encryptedFile != null && !encryptedFile.isEmpty()) {
            requireNonBlankString(metadata, "ivBase64");
            requireNonBlankString(metadata, "ciphertextSha256");
        }
    }

    private void requireNonBlankString(Map<String, Object> metadata, String field) {
        Object value = metadata.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " is required");
        }
    }
}
