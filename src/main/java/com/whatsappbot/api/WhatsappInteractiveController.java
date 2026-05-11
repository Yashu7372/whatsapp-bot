package com.whatsappbot.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.whatsappbot.application.interactive.WhatsappNativeInteractiveService;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/tenants/{tenantId}/whatsapp/interactive")
public class WhatsappInteractiveController {

    private final TenantRepository tenantRepository;
    private final WhatsappNativeInteractiveService service;

    public WhatsappInteractiveController(TenantRepository tenantRepository, WhatsappNativeInteractiveService service) {
        this.tenantRepository = tenantRepository;
        this.service = service;
    }

    @PostMapping("/buttons")
    public ResponseEntity<JsonNode> sendButtons(@PathVariable UUID tenantId, @RequestBody SendButtonsRequest request) {
        TenantEntity tenant = tenant(tenantId);
        return ResponseEntity.ok(service.sendReplyButtons(tenant, request.to(), request.body(), request.buttons()));
    }

    @PostMapping("/list")
    public ResponseEntity<JsonNode> sendList(@PathVariable UUID tenantId, @RequestBody SendListRequest request) {
        TenantEntity tenant = tenant(tenantId);
        return ResponseEntity.ok(service.sendListMenu(tenant, request.to(), request.header(), request.body(), request.buttonText(), request.rows()));
    }

    @PostMapping("/catalog")
    public ResponseEntity<JsonNode> sendCatalog(@PathVariable UUID tenantId, @RequestBody SendCatalogRequest request) {
        TenantEntity tenant = tenant(tenantId);
        return ResponseEntity.ok(service.sendCatalogMessage(tenant, request.to(), request.body(), request.footer()));
    }

    @PostMapping("/multi-product")
    public ResponseEntity<JsonNode> sendMultiProduct(@PathVariable UUID tenantId, @RequestBody SendMultiProductRequest request) {
        TenantEntity tenant = tenant(tenantId);
        return ResponseEntity.ok(service.sendMultiProductMenu(tenant, request.to(), request.header(), request.body(), request.footer()));
    }

    @PostMapping("/flow")
    public ResponseEntity<JsonNode> triggerFlow(@PathVariable UUID tenantId, @RequestBody TriggerFlowRequest request) {
        TenantEntity tenant = tenant(tenantId);
        return ResponseEntity.ok(service.triggerFlow(tenant, request.to(), request.flowKey(), request.body(), request.initialData()));
    }

    private TenantEntity tenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
    }

    public record SendButtonsRequest(String to, String body, Map<String, String> buttons) {}
    public record SendListRequest(String to, String header, String body, String buttonText, Map<String, String> rows) {}
    public record SendCatalogRequest(String to, String body, String footer) {}
    public record SendMultiProductRequest(String to, String header, String body, String footer) {}
    public record TriggerFlowRequest(String to, String flowKey, String body, Map<String, Object> initialData) {}
}
