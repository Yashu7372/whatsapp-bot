package com.yashu.projectcontrol.channel.whatsapp;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@RestController
@RequestMapping("/webhooks/whatsapp")
public class WhatsAppWebhookController {

    private final WhatsAppChannelProperties properties;
    private final WhatsAppChannelService channelService;
    private final ObjectMapper objectMapper;

    public WhatsAppWebhookController(
            WhatsAppChannelProperties properties,
            WhatsAppChannelService channelService,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.channelService = channelService;
        this.objectMapper = objectMapper;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode)
                && !properties.verifyToken().isBlank()
                && MessageDigest.isEqual(
                        properties.verifyToken().getBytes(StandardCharsets.UTF_8),
                        (token == null ? "" : token).getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.ok(challenge == null ? "" : challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Webhook verification failed");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> receive(
            @RequestBody String body,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        if (!validSignature(body, signature)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid webhook signature");
        }

        final JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid WhatsApp webhook JSON", ex);
        }
        dispatchTextMessages(root);
        return ResponseEntity.ok("EVENT_RECEIVED");
    }

    private void dispatchTextMessages(JsonNode root) {
        JsonNode entries = root == null ? null : root.get("entry");
        if (entries == null || !entries.isArray()) return;
        for (JsonNode entry : entries) {
            JsonNode changes = entry.get("changes");
            if (changes == null || !changes.isArray()) continue;
            for (JsonNode change : changes) {
                JsonNode value = change.get("value");
                JsonNode messages = value == null ? null : value.get("messages");
                if (messages == null || !messages.isArray()) continue;
                for (JsonNode message : messages) {
                    if (!"text".equals(text(message, "type"))) continue;
                    JsonNode textNode = message.get("text");
                    String text = textNode == null ? "" : text(textNode, "body");
                    if (text.isBlank()) continue;
                    channelService.handleInboundText(
                            text(message, "from"),
                            text(message, "id"),
                            text);
                }
            }
        }
    }

    private boolean validSignature(String body, String signature) {
        String secret = properties.appSecret();
        if (secret.isBlank()) return true;
        if (signature == null || !signature.startsWith("sha256=")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(
                    mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not verify WhatsApp webhook signature", ex);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asString();
    }
}
