package com.whatsappbot.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhook")
public class WebhookConfigController {

    @Value("${whatsapp.verify-token}")
    private String verifyToken;

    @Value("${server.port:8080}")
    private int serverPort;

    // This returns the raw verify token — worth being fail-closed and self-documenting here rather
    // than relying solely on FeatureAuthorizationInterceptor's path-regex match.
    @GetMapping("/config")
    @PreAuthorize("@perm.view('SETTINGS_WEBHOOK')")
    public ResponseEntity<WebhookConfigResponse> getConfig() {
        return ResponseEntity.ok(new WebhookConfigResponse("/webhook", verifyToken, serverPort));
    }

    record WebhookConfigResponse(String webhookPath, String verifyToken, int port) {}
}
