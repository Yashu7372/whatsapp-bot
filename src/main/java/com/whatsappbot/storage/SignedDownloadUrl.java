package com.whatsappbot.storage;

public record SignedDownloadUrl(
        String downloadUrl,
        long expiresInSeconds
) {}
