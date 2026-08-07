package com.whatsappbot.storage;

public record SignedUploadUrl(
        String uploadUrl,
        String uploadToken,
        String objectKey,
        long expiresInSeconds
) {}
