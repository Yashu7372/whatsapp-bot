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
     * Writes bytes the backend already holds. Used only where the source genuinely cannot upload
     * straight to the bucket itself — a document pulled from the WhatsApp Graph API, or a scanned
     * upload-link submission where the bytes had to pass through malware scanning first. Every
     * caller streams from a temp file it deletes immediately after, so nothing here holds a file
     * in memory or lingers on local disk.
     */
    StoredObjectRef putObject(UUID tenantId, String fileName, String contentType,
                              InputStream data, long sizeBytes);

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
