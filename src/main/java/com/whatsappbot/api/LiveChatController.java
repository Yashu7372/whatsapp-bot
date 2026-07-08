package com.whatsappbot.api;

import com.whatsappbot.application.livechat.ConversationActionRequest;
import com.whatsappbot.application.livechat.LiveChatService;
import com.whatsappbot.application.tenant.TenantService;
import com.whatsappbot.domain.conversation.ConversationEntity;
import com.whatsappbot.domain.tenant.TenantEntity;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/tenants/{phoneNumberId}/conversations")
@RequiredArgsConstructor
public class LiveChatController {

    private final TenantService tenantService;
    private final LiveChatService liveChatService;

    @PostMapping("/{conversationId}/intervene")
    public ConversationEntity intervene(@AuthenticationPrincipal Claims claims,
                                        @PathVariable String phoneNumberId,
                                        @PathVariable UUID conversationId,
                                        @RequestBody ConversationActionRequest request) {
        TenantEntity tenant = resolveAuthorizedTenant(claims, phoneNumberId);
        return liveChatService.intervene(tenant, conversationId, request.agentId(), request.notes());
    }

    @PostMapping("/{conversationId}/assign")
    public ConversationEntity assign(@AuthenticationPrincipal Claims claims,
                                     @PathVariable String phoneNumberId,
                                     @PathVariable UUID conversationId,
                                     @RequestBody ConversationActionRequest request) {
        TenantEntity tenant = resolveAuthorizedTenant(claims, phoneNumberId);
        return liveChatService.assign(tenant, conversationId, request.agentId(), request.notes());
    }

    @PostMapping("/{conversationId}/transfer")
    public ConversationEntity transfer(@AuthenticationPrincipal Claims claims,
                                       @PathVariable String phoneNumberId,
                                       @PathVariable UUID conversationId,
                                       @RequestBody ConversationActionRequest request) {
        TenantEntity tenant = resolveAuthorizedTenant(claims, phoneNumberId);
        return liveChatService.transfer(tenant, conversationId, request.agentId(), request.toAgentId(), request.notes());
    }

    @PostMapping("/{conversationId}/resolve")
    public ConversationEntity resolve(@AuthenticationPrincipal Claims claims,
                                      @PathVariable String phoneNumberId,
                                      @PathVariable UUID conversationId,
                                      @RequestBody ConversationActionRequest request) {
        TenantEntity tenant = resolveAuthorizedTenant(claims, phoneNumberId);
        return liveChatService.resolve(tenant, conversationId, request.agentId(), request.notes());
    }

    @PostMapping("/{conversationId}/reopen-bot")
    public ConversationEntity reopenBot(@AuthenticationPrincipal Claims claims,
                                        @PathVariable String phoneNumberId,
                                        @PathVariable UUID conversationId,
                                        @RequestBody ConversationActionRequest request) {
        TenantEntity tenant = resolveAuthorizedTenant(claims, phoneNumberId);
        return liveChatService.reopenForBot(tenant, conversationId, request.agentId(), request.notes());
    }

    /**
     * The phoneNumberId in the path is caller-supplied — without checking it
     * against the JWT's tenant, an authenticated user of tenant A could act on
     * tenant B's conversations just by using B's phone number id in the URL.
     */
    private TenantEntity resolveAuthorizedTenant(Claims claims, String phoneNumberId) {
        TenantEntity tenant = tenantService.resolveActiveTenant(phoneNumberId);
        UUID jwtTenantId = UUID.fromString((String) claims.get("tenantId"));
        if (!tenant.getId().equals(jwtTenantId)) {
            throw new AccessDeniedException("Tenant mismatch between token and requested resource");
        }
        return tenant;
    }
}
