package com.whatsappbot.storage;

/** What a provider hands back after a server-initiated write — never the bytes themselves. */
public record StoredObjectRef(String objectKey, long sizeBytes, String checksumSha256) {}
