package com.yashu.projectcontrol.channel.whatsapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WhatsAppChannelProperties {

    private final boolean enabled;
    private final String verifyToken;
    private final String accessToken;
    private final String phoneNumberId;
    private final String apiVersion;
    private final String appSecret;
    private final String localUserEmail;
    private final String localUserNumber;

    public WhatsAppChannelProperties(
            @Value("${project-control.whatsapp.enabled:false}") boolean enabled,
            @Value("${project-control.whatsapp.verify-token:}") String verifyToken,
            @Value("${project-control.whatsapp.access-token:}") String accessToken,
            @Value("${project-control.whatsapp.phone-number-id:}") String phoneNumberId,
            @Value("${project-control.whatsapp.api-version:v23.0}") String apiVersion,
            @Value("${project-control.whatsapp.app-secret:}") String appSecret,
            @Value("${project-control.whatsapp.local-user-email:}") String localUserEmail,
            @Value("${project-control.whatsapp.local-user-number:}") String localUserNumber) {
        this.enabled = enabled;
        this.verifyToken = trim(verifyToken);
        this.accessToken = trim(accessToken);
        this.phoneNumberId = trim(phoneNumberId);
        this.apiVersion = trim(apiVersion);
        this.appSecret = trim(appSecret);
        this.localUserEmail = trim(localUserEmail);
        this.localUserNumber = trim(localUserNumber);
    }

    public boolean enabled() { return enabled; }
    public String verifyToken() { return verifyToken; }
    public String accessToken() { return accessToken; }
    public String phoneNumberId() { return phoneNumberId; }
    public String apiVersion() { return apiVersion; }
    public String appSecret() { return appSecret; }
    public String localUserEmail() { return localUserEmail; }
    public String localUserNumber() { return localUserNumber; }

    public boolean hasLocalIdentityBinding() {
        return !localUserEmail.isBlank() && !localUserNumber.isBlank();
    }

    public void requireSendConfiguration() {
        if (!enabled) throw new IllegalStateException("Project Control WhatsApp channel is disabled");
        if (accessToken.isBlank()) throw new IllegalStateException("WHATSAPP_ACCESS_TOKEN is required");
        if (phoneNumberId.isBlank()) throw new IllegalStateException("WHATSAPP_PHONE_NUMBER_ID is required");
        if (apiVersion.isBlank()) throw new IllegalStateException("WHATSAPP_API_VERSION is required");
    }

    public static String normalizeAddress(String value) {
        if (value == null) return "";
        return value.replaceAll("[^0-9]", "");
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
