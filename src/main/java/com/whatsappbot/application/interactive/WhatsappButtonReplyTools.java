package com.whatsappbot.application.interactive;

import com.whatsappbot.domain.interactive.WhatsappButtonReplyEntity;
import com.whatsappbot.domain.interactive.WhatsappButtonReplyRepository;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import com.whatsappbot.infrastructure.tenant.TenantContext;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Lets the AI discover which quick-reply button ids actually exist for the
 * current tenant before offering them via sendReplyButtonsToCustomer. This
 * is what keeps {@code whatsapp_button_replies} the single source of truth:
 * the AI is expected to only offer ids returned here, never invent new ones,
 * so {@link com.whatsappbot.application.webhook.WhatsappInteractiveInboundHandler}
 * can reliably route the tap back to a registered reply regardless of
 * business type.
 */
@Component
public class WhatsappButtonReplyTools {

    private final WhatsappButtonReplyRepository buttonReplyRepository;
    private final TenantRepository tenantRepository;

    public WhatsappButtonReplyTools(WhatsappButtonReplyRepository buttonReplyRepository, TenantRepository tenantRepository) {
        this.buttonReplyRepository = buttonReplyRepository;
        this.tenantRepository = tenantRepository;
    }

    @Tool("List the quick-reply button ids and titles registered for this business. "
            + "ALWAYS call this before sendReplyButtonsToCustomer and use only the ids returned here — "
            + "never invent a new button id. If no relevant registered button exists for what the "
            + "customer needs, send plain text instead of a button.")
    public String listAvailableQuickReplyButtons() {
        TenantEntity tenant = currentTenant();
        List<WhatsappButtonReplyEntity> buttons =
                buttonReplyRepository.findByTenantAndActiveTrueOrderBySortOrderAsc(tenant);

        if (buttons.isEmpty()) {
            return "NO_QUICK_REPLY_BUTTONS_REGISTERED. Do not call sendReplyButtonsToCustomer; use plain text instead.";
        }

        StringBuilder result = new StringBuilder("AVAILABLE_QUICK_REPLY_BUTTONS:\n");
        for (WhatsappButtonReplyEntity button : buttons) {
            result.append("- id=\"").append(button.getButtonId())
                    .append("\", title=\"").append(button.getButtonTitle()).append("\"");
            if (button.getDescription() != null && !button.getDescription().isBlank()) {
                result.append(" — ").append(button.getDescription());
            }
            result.append("\n");
        }
        return result.toString().trim();
    }

    private TenantEntity currentTenant() {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant not found in tool context: " + tenantId));
    }
}