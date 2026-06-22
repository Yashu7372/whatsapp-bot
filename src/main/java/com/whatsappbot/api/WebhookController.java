package com.whatsappbot.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.whatsappbot.application.webhook.WebhookOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WebhookController {

    @Value("${whatsapp.verify-token}")
    private String verifyToken;

    private final WebhookOutboxService webhookOutboxService;

    @GetMapping
    public ResponseEntity<String> verifyWebhook(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {

        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            log.info("Webhook verified by Meta");
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(403).build();
    }

    @PostMapping
    public ResponseEntity<Void> receiveMessage(@RequestBody JsonNode payload) {
        try {
            webhookOutboxService.enqueue(payload);
        } catch (Exception e) {
            // Enqueue failure is logged but still returns 200 — Meta must not retry on our internal errors.
            log.error("Failed to enqueue inbound WhatsApp webhook payload", e);
        }
        return ResponseEntity.ok().build();
    }
}
