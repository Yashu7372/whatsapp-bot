package com.whatsappbot.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.domain.tenant.BusinessType;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/crm/workspace")
@RequiredArgsConstructor
public class CrmWorkspaceController {

    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper;

    @Value("${whatsapp.verify-token}")
    private String verifyToken;

    @GetMapping
    public ResponseEntity<WorkspaceResponse> getWorkspace(@AuthenticationPrincipal Claims claims) {
        return ResponseEntity.ok(toResponse(getTenant(claims)));
    }

    @PutMapping
    public ResponseEntity<WorkspaceResponse> updateWorkspace(
            @AuthenticationPrincipal Claims claims,
            @RequestBody UpdateWorkspaceRequest request) {

        TenantEntity tenant = getTenant(claims);
        if (request.name() != null && !request.name().isBlank()) {
            tenant.setBusinessName(request.name().trim());
        }
        if (request.businessType() != null && !request.businessType().isBlank()) {
            tenant.setCrmBusinessType(request.businessType().trim().toLowerCase(Locale.ROOT));
            tenant.setBusinessType(mapBusinessType(request.businessType()));
        }
        if (request.businessHours() != null && !request.businessHours().isBlank()) {
            tenant.setBusinessHours(request.businessHours().trim());
        }
        if (request.whatsappPhoneId() != null && !request.whatsappPhoneId().isBlank()) {
            tenant.setPhoneNumberId(request.whatsappPhoneId().trim());
        }
        if (request.whatsappToken() != null && !request.whatsappToken().isBlank()) {
            tenant.setAccessTokenEncrypted(request.whatsappToken().trim());
        }
        if (request.whatsappNumber() != null) {
            tenant.setWhatsappNumber(request.whatsappNumber().isBlank() ? null : request.whatsappNumber().trim());
        }
        if (request.faq() != null) {
            tenant.setFaqJson(writeFaqJson(request.faq()));
        }

        return ResponseEntity.ok(toResponse(tenantRepository.save(tenant)));
    }

    private TenantEntity getTenant(Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
    }

    private WorkspaceResponse toResponse(TenantEntity tenant) {
        return new WorkspaceResponse(
                tenant.getBusinessName(),
                tenant.getCrmBusinessType(),
                tenant.getBusinessHours(),
                tenant.getPhoneNumberId(),
                tenant.getAccessTokenEncrypted(),
                tenant.getWhatsappNumber(),
                readFaqJson(tenant.getFaqJson()),
                verifyToken,
                "starter",
                tenant.getPhoneNumberId() != null && !tenant.getPhoneNumberId().isBlank()
                        && tenant.getAccessTokenEncrypted() != null && !tenant.getAccessTokenEncrypted().isBlank()
        );
    }

    private List<FaqItem> readFaqJson(String json) {
        try {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse tenant FAQ JSON", e);
        }
    }

    private String writeFaqJson(List<FaqItem> faq) {
        try {
            return objectMapper.writeValueAsString(faq);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize tenant FAQ JSON", e);
        }
    }

    private BusinessType mapBusinessType(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "dentist", "clinic" -> BusinessType.CLINIC;
            case "salon" -> BusinessType.SALON;
            case "car_rental", "automobile" -> BusinessType.AUTOMOBILE;
            case "restaurant" -> BusinessType.RESTAURANT;
            case "retail" -> BusinessType.RETAIL;
            default -> BusinessType.GENERAL_SUPPORT;
        };
    }

    public record FaqItem(String q, String a) {}

    public record WorkspaceResponse(String name,
                                    String businessType,
                                    String businessHours,
                                    String whatsappPhoneId,
                                    String whatsappToken,
                                    String whatsappNumber,
                                    List<FaqItem> faq,
                                    String webhookVerifyToken,
                                    String plan,
                                    boolean whatsappConnected) {}

    public record UpdateWorkspaceRequest(String name,
                                         String businessType,
                                         String businessHours,
                                         String whatsappPhoneId,
                                         String whatsappToken,
                                         String whatsappNumber,
                                         List<FaqItem> faq) {}
}
