package com.whatsappbot.infrastructure.whatsapp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.domain.template.WhatsAppMessageTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class WhatsAppTemplatePayloadBuilder {

    private final ObjectMapper objectMapper;

    public WhatsAppTemplatePayloadBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> buildPayload(WhatsAppMessageTemplate template,
                                            String toPhoneNumber,
                                            String componentsJson,
                                            List<String> bodyTextParameters) {
        Map<String, Object> templateNode = new LinkedHashMap<>();
        templateNode.put("name", template.getMetaTemplateName());
        templateNode.put("language", Map.of("code", template.getLanguageCode()));

        Object components = resolveComponents(template, componentsJson, bodyTextParameters);
        if (components != null) {
            templateNode.put("components", components);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", toPhoneNumber);
        payload.put("type", "template");
        payload.put("template", templateNode);
        return payload;
    }

    private Object resolveComponents(WhatsAppMessageTemplate template,
                                     String componentsJson,
                                     List<String> bodyTextParameters) {
        if (componentsJson != null && !componentsJson.isBlank()) {
            return parseJsonArray(componentsJson, "componentsJson");
        }
        if (bodyTextParameters != null && !bodyTextParameters.isEmpty()) {
            return bodyParameters(bodyTextParameters);
        }
        if (template.getDefaultComponentsJson() != null && !template.getDefaultComponentsJson().isBlank()) {
            return parseJsonArray(template.getDefaultComponentsJson(), "defaultComponentsJson");
        }
        return null;
    }

    private List<Map<String, Object>> bodyParameters(List<String> values) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (String value : values) {
            parameters.add(Map.of("type", "text", "text", value == null ? "" : value));
        }
        return List.of(Map.of(
                "type", "body",
                "parameters", parameters
        ));
    }

    private Object parseJsonArray(String json, String fieldName) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(fieldName + " must be a valid WhatsApp Graph API components JSON array", e);
        }
    }
}
