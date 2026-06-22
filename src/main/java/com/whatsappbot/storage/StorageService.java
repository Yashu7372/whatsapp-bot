package com.whatsappbot.storage;

import java.io.InputStream;
import java.util.UUID;

public interface StorageService {
    StoredFile store(UUID tenantId, String originalName, String contentType, InputStream data, long sizeBytes);
    InputStream retrieve(String storedPath);
    void delete(String storedPath);
}
