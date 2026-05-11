package com.whatsappbot.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.whatsappbot.application.webhook.WebhookApplicationService;
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

    private final WebhookApplicationService webhookApplicationService;

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
            webhookApplicationService.handleIncomingWebhook(payload);
        } catch (Exception e) {
            // Meta expects a 200 quickly. Log internally and avoid repeated webhook retries for app-side failures.
            log.error("Failed to process WhatsApp webhook", e);
        }
        return ResponseEntity.ok().build();
    }
}
