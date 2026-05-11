package com.whatsappbot.application.interactive;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.infrastructure.tenant.TenantContext;
import com.whatsappbot.domain.tenant.TenantRepository;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class WhatsappNativeInteractiveTools {

    private final WhatsappNativeInteractiveService interactiveService;
    private final TenantRepository tenantRepository;

    public WhatsappNativeInteractiveTools(WhatsappNativeInteractiveService interactiveService, TenantRepository tenantRepository) {
        this.interactiveService = interactiveService;
        this.tenantRepository = tenantRepository;
    }

    @Tool("Send WhatsApp reply buttons to the current customer. Use for yes/no or up to 3 quick choices.")
    public String sendReplyButtonsToCustomer(String customerWhatsappId, String message, Map<String, String> buttonIdToTitle) {
        TenantEntity tenant = currentTenant();
        interactiveService.sendReplyButtons(tenant, customerWhatsappId, message, buttonIdToTitle);
        return "Reply buttons sent.";
    }

    @Tool("Send a WhatsApp list menu to the current customer. Use for menus, categories, service choices, or up to 10 options.")
    public String sendListMenuToCustomer(String customerWhatsappId, String title, String message, String buttonText, Map<String, String> rowIdToTitle) {
        TenantEntity tenant = currentTenant();
        interactiveService.sendListMenu(tenant, customerWhatsappId, title, message, buttonText, rowIdToTitle);
        return "List menu sent.";
    }

    @Tool("Send the business WhatsApp catalog entry point to the current customer.")
    public String sendCatalogToCustomer(String customerWhatsappId, String message) {
        TenantEntity tenant = currentTenant();
        interactiveService.sendCatalogMessage(tenant, customerWhatsappId, message, "Browse products and add items to cart.");
        return "Catalog message sent.";
    }

    @Tool("Send a WhatsApp multi-product menu to the current customer from the tenant product catalog.")
    public String sendMultiProductMenuToCustomer(String customerWhatsappId, String title, String message) {
        TenantEntity tenant = currentTenant();
        interactiveService.sendMultiProductMenu(tenant, customerWhatsappId, title, message, "Select products to continue.");
        return "Multi-product message sent.";
    }

    @Tool("Send a single WhatsApp product card to the current customer. Use when the customer asks about one exact product.")
    public String sendSingleProductToCustomer(String customerWhatsappId, String message, String productId) {
        TenantEntity tenant = currentTenant();
        interactiveService.sendSingleProduct(tenant, customerWhatsappId, message, UUID.fromString(productId));
        return "Single product message sent.";
    }

    @Tool("Send a WhatsApp call-to-action URL button to the current customer.")
    public String sendCtaUrlToCustomer(String customerWhatsappId, String message, String buttonText, String url) {
        TenantEntity tenant = currentTenant();
        interactiveService.sendCtaUrl(tenant, customerWhatsappId, message, buttonText, url);
        return "CTA URL sent.";
    }

    @Tool("Ask the current customer to share their WhatsApp location.")
    public String requestCustomerLocation(String customerWhatsappId, String message) {
        TenantEntity tenant = currentTenant();
        interactiveService.requestLocation(tenant, customerWhatsappId, message);
        return "Location request sent.";
    }

    @Tool("Trigger a registered WhatsApp Flow for the current customer. Use for booking forms, address capture, lead forms, real estate viewing requests, checkout forms, or structured data capture.")
    public String triggerFlowForCustomer(String customerWhatsappId, String flowKey, String message, Map<String, Object> initialData) {
        TenantEntity tenant = currentTenant();
        interactiveService.triggerFlow(tenant, customerWhatsappId, flowKey, message, initialData);
        return "WhatsApp Flow triggered.";
    }

    private TenantEntity currentTenant() {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant not found in tool context: " + tenantId));
    }
}
