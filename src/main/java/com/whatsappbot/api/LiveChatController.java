package com.whatsappbot.api;

import com.whatsappbot.application.livechat.ConversationActionRequest;
import com.whatsappbot.application.livechat.LiveChatService;
import com.whatsappbot.application.tenant.TenantService;
import com.whatsappbot.domain.conversation.ConversationEntity;
import com.whatsappbot.domain.tenant.TenantEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/tenants/{phoneNumberId}/conversations")
@RequiredArgsConstructor
public class LiveChatController {

    private final TenantService tenantService;
    private final LiveChatService liveChatService;

    @PostMapping("/{conversationId}/intervene")
    public ConversationEntity intervene(@PathVariable String phoneNumberId,
                                        @PathVariable UUID conversationId,
                                        @RequestBody ConversationActionRequest request) {
        TenantEntity tenant = tenantService.resolveActiveTenant(phoneNumberId);
        return liveChatService.intervene(tenant, conversationId, request.agentId(), request.notes());
    }

    @PostMapping("/{conversationId}/assign")
    public ConversationEntity assign(@PathVariable String phoneNumberId,
                                     @PathVariable UUID conversationId,
                                     @RequestBody ConversationActionRequest request) {
        TenantEntity tenant = tenantService.resolveActiveTenant(phoneNumberId);
        return liveChatService.assign(tenant, conversationId, request.agentId(), request.notes());
    }

    @PostMapping("/{conversationId}/transfer")
    public ConversationEntity transfer(@PathVariable String phoneNumberId,
                                       @PathVariable UUID conversationId,
                                       @RequestBody ConversationActionRequest request) {
        TenantEntity tenant = tenantService.resolveActiveTenant(phoneNumberId);
        return liveChatService.transfer(tenant, conversationId, request.agentId(), request.toAgentId(), request.notes());
    }

    @PostMapping("/{conversationId}/resolve")
    public ConversationEntity resolve(@PathVariable String phoneNumberId,
                                      @PathVariable UUID conversationId,
                                      @RequestBody ConversationActionRequest request) {
        TenantEntity tenant = tenantService.resolveActiveTenant(phoneNumberId);
        return liveChatService.resolve(tenant, conversationId, request.agentId(), request.notes());
    }

    @PostMapping("/{conversationId}/reopen-bot")
    public ConversationEntity reopenBot(@PathVariable String phoneNumberId,
                                        @PathVariable UUID conversationId,
                                        @RequestBody ConversationActionRequest request) {
        TenantEntity tenant = tenantService.resolveActiveTenant(phoneNumberId);
        return liveChatService.reopenForBot(tenant, conversationId, request.agentId(), request.notes());
    }
}
