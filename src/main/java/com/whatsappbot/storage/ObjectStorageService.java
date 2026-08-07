package com.whatsappbot.storage;

import java.io.InputStream;
import java.util.UUID;

public interface ObjectStorageService {

    /**
     * Returns a pre-signed URL the client can use to upload directly.
     * The backend must not receive the file bytes.
     */
    SignedUploadUrl createUploadUrl(UUID tenantId, String fileName, String contentType, long sizeBytes);

    /**
     * Returns a short-lived download URL for a private object.
     */
    SignedDownloadUrl createDownloadUrl(UUID tenantId, String objectKey);

    /**
     * Deletes an object from storage. Best-effort — logs on failure.
     */
    void deleteObject(UUID tenantId, String objectKey);

    /**
     * Provider identifier stored in media_assets.storage_provider.
     */
    String providerCode();
}
