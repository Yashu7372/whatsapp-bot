package com.whatsappbot.application.livechat;

import java.util.UUID;

public record ConversationActionRequest(
        UUID agentId,
        UUID toAgentId,
        String notes
) {
}
