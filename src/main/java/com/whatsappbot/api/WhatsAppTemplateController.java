package com.whatsappbot.api;

import com.whatsappbot.application.template.SendTemplateRequest;
import com.whatsappbot.application.template.TemplateSendResult;
import com.whatsappbot.application.template.TemplateSummary;
import com.whatsappbot.application.template.WhatsAppTemplateService;
import com.whatsappbot.application.tenant.TenantService;
import com.whatsappbot.domain.template.TemplateRecipientType;
import com.whatsappbot.domain.template.TemplateSentBy;
import com.whatsappbot.domain.tenant.TenantEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tenants/{phoneNumberId}/templates")
@RequiredArgsConstructor
public class WhatsAppTemplateController {

    private final TenantService tenantService;
    private final WhatsAppTemplateService templateService;

    @GetMapping
    public List<TemplateSummary> list(@PathVariable String phoneNumberId) {
        TenantEntity tenant = tenantService.resolveActiveTenant(phoneNumberId);
        return templateService.listTemplates(tenant);
    }

    @PostMapping("/send")
    public TemplateSendResult send(@PathVariable String phoneNumberId,
                                   @RequestBody SendTemplateRequest request) {
        TenantEntity tenant = tenantService.resolveActiveTenant(phoneNumberId);
        return templateService.sendTemplate(
                tenant,
                null,
                request.templateCode(),
                request.languageCode(),
                request.toPhoneNumber(),
                request.componentsJson(),
                request.bodyTextParameters(),
                TemplateRecipientType.MANUAL_AGENT_SEND,
                TemplateSentBy.AGENT
        );
    }
}
