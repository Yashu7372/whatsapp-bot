package com.whatsappbot.application.context;

import com.whatsappbot.domain.contact.ContactEntity;
import com.whatsappbot.domain.conversation.ConversationEntity;
import com.whatsappbot.domain.tenant.TenantEntity;

public final class TenantExecutionContext {

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private TenantExecutionContext() {
    }

    public static void set(TenantEntity tenant, ContactEntity contact, ConversationEntity conversation, String customerPhoneNumber) {
        CURRENT.set(new Context(tenant, contact, conversation, customerPhoneNumber));
    }

    public static Context getRequired() {
        Context context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException("TenantExecutionContext is not set for the current AI/tool call");
        }
        return context;
    }

    public static void clear() {
        CURRENT.remove();
    }

    public record Context(
            TenantEntity tenant,
            ContactEntity contact,
            ConversationEntity conversation,
            String customerPhoneNumber
    ) {
    }
}
