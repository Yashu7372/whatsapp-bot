package com.whatsappbot.application.interactive;

import com.fasterxml.jackson.databind.JsonNode;
import com.whatsappbot.domain.interactive.WhatsappFlowRegistryEntity;
import com.whatsappbot.domain.interactive.WhatsappFlowRegistryRepository;
import com.whatsappbot.domain.product.ProductCategoryEntity;
import com.whatsappbot.domain.product.ProductCategoryRepository;
import com.whatsappbot.domain.product.ProductEntity;
import com.whatsappbot.domain.product.ProductRepository;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.infrastructure.whatsapp.WhatsappInteractiveGraphClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class WhatsappNativeInteractiveService {

    private final WhatsappInteractiveGraphClient graphClient;
    private final ProductRepository productRepository;
    private final ProductCategoryRepository categoryRepository;
    private final WhatsappFlowRegistryRepository flowRegistryRepository;

    public WhatsappNativeInteractiveService(
            WhatsappInteractiveGraphClient graphClient,
            ProductRepository productRepository,
            ProductCategoryRepository categoryRepository,
            WhatsappFlowRegistryRepository flowRegistryRepository
    ) {
        this.graphClient = graphClient;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.flowRegistryRepository = flowRegistryRepository;
    }

    public JsonNode sendReplyButtons(TenantEntity tenant, String to, String bodyText, Map<String, String> buttons) {
        List<Map<String, Object>> buttonList = buttons.entrySet().stream()
                .limit(3)
                .map(e -> Map.<String, Object>of(
                        "type", "reply",
                        "reply", Map.of("id", e.getKey(), "title", e.getValue())
                ))
                .toList();

        return graphClient.sendRawMessage(tenant, Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "button",
                        "body", Map.of("text", bodyText),
                        "action", Map.of("buttons", buttonList)
                )
        ));
    }

    public JsonNode sendListMenu(TenantEntity tenant, String to, String headerText, String bodyText, String buttonText, Map<String, String> rows) {
        List<Map<String, Object>> rowList = rows.entrySet().stream()
                .limit(10)
                .map(e -> Map.<String, Object>of(
                        "id", e.getKey(),
                        "title", e.getValue()
                ))
                .toList();

        return graphClient.sendRawMessage(tenant, Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "list",
                        "header", Map.of("type", "text", "text", headerText),
                        "body", Map.of("text", bodyText),
                        "action", Map.of(
                                "button", buttonText,
                                "sections", List.of(Map.of("title", headerText, "rows", rowList))
                        )
                )
        ));
    }

    public JsonNode sendCatalogMessage(TenantEntity tenant, String to, String bodyText, String footerText) {
        return graphClient.sendRawMessage(tenant, Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "catalog_message",
                        "body", Map.of("text", bodyText),
                        "footer", Map.of("text", footerText),
                        "action", Map.of("name", "catalog_message")
                )
        ));
    }

    public JsonNode sendSingleProduct(TenantEntity tenant, String to, String bodyText, UUID productId) {
        ProductEntity product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        return graphClient.sendRawMessage(tenant, Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "product",
                        "body", Map.of("text", bodyText),
                        "action", Map.of(
                                "catalog_id", product.getWhatsappCatalogId(),
                                "product_retailer_id", product.getRetailerId()
                        )
                )
        ));
    }

    @Transactional(readOnly = true)
    public JsonNode sendMultiProductMenu(TenantEntity tenant, String to, String headerText, String bodyText, String footerText) {
        List<ProductCategoryEntity> categories = categoryRepository.findByTenantIdAndActiveTrueOrderBySortOrderAscNameAsc(tenant.getId());

        List<Map<String, Object>> sections = categories.stream()
                .map(category -> {
                    List<ProductEntity> products = productRepository.findByTenantIdAndCategoryIdAndActiveTrueOrderByNameAsc(tenant.getId(), category.getId());
                    List<Map<String, Object>> productItems = products.stream()
                            .limit(30)
                            .filter(p -> p.getRetailerId() != null && !p.getRetailerId().isBlank())
                            .map(p -> Map.<String, Object>of("product_retailer_id", p.getRetailerId()))
                            .toList();
                    return Map.<String, Object>of("title", category.getName(), "product_items", productItems);
                })
                .filter(section -> !((List<?>) section.get("product_items")).isEmpty())
                .limit(10)
                .collect(Collectors.toList());

        if (sections.isEmpty()) {
            throw new IllegalStateException("No active catalog products configured for tenant " + tenant.getTenantCode());
        }

        String catalogId = productRepository.findTop30ByTenantIdAndActiveTrueOrderByCategoryAscNameAsc(tenant.getId())
                .stream()
                .map(ProductEntity::getWhatsappCatalogId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No WhatsApp catalog id configured in products"));

        return graphClient.sendRawMessage(tenant, Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "product_list",
                        "header", Map.of("type", "text", "text", headerText),
                        "body", Map.of("text", bodyText),
                        "footer", Map.of("text", footerText),
                        "action", Map.of("catalog_id", catalogId, "sections", sections)
                )
        ));
    }

    public JsonNode sendCtaUrl(TenantEntity tenant, String to, String bodyText, String buttonText, String url) {
        return graphClient.sendRawMessage(tenant, Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "cta_url",
                        "body", Map.of("text", bodyText),
                        "action", Map.of("name", "cta_url", "parameters", Map.of("display_text", buttonText, "url", url))
                )
        ));
    }

    public JsonNode requestLocation(TenantEntity tenant, String to, String bodyText) {
        return graphClient.sendRawMessage(tenant, Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "location_request_message",
                        "body", Map.of("text", bodyText),
                        "action", Map.of("name", "send_location")
                )
        ));
    }

    public JsonNode triggerFlow(TenantEntity tenant, String to, String flowKey, String bodyText, Map<String, Object> flowData) {
        WhatsappFlowRegistryEntity flow = flowRegistryRepository.findByTenantIdAndFlowKeyAndActiveTrue(tenant.getId(), flowKey)
                .orElseThrow(() -> new IllegalArgumentException("No active flow registered for key: " + flowKey));

        Map<String, Object> flowParameters = new LinkedHashMap<>();
        flowParameters.put("flow_message_version", "3");
        flowParameters.put("flow_token", UUID.randomUUID().toString());
        flowParameters.put("flow_id", flow.getFlowId());
        flowParameters.put("flow_cta", flow.getFlowCta());
        flowParameters.put("mode", "published");
        if (flow.getScreenId() != null) {
            flowParameters.put("flow_action", "navigate");
            flowParameters.put("flow_action_payload", Map.of("screen", flow.getScreenId(), "data", flowData == null ? Map.of() : flowData));
        }

        return graphClient.sendRawMessage(tenant, Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "flow",
                        "body", Map.of("text", bodyText),
                        "action", Map.of("name", "flow", "parameters", flowParameters)
                )
        ));
    }
}
