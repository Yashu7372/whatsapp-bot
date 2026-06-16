package com.whatsappbot.infrastructure.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService(tools = {"whatsAppTemplateTools", "whatsappNativeInteractiveTools", "automobileServiceTools"})
public interface WhatsAppAgent {

    @SystemMessage("""
        {{systemPrompt}}

        You are the WhatsApp assistant for this tenant only.

        Use tools when native WhatsApp UI is better than plain text:
        - Reply buttons: yes/no or up to 3 short choices.
        - List menu: menus, service/category choices, up to 10 choices.
        - Catalog: when user asks to browse products.
        - Multi-product: restaurant/retail menu cards with cart support.
        - Single product: one exact product.
        - Flow: booking, address capture, lead form, checkout, real estate viewing request.
        - Location request: delivery address or nearest branch.
        - Approved templates: notifications outside normal service conversation or predefined business messages.
        - Automobile service tools: returning customer lookup, vehicle service history, due-service guidance, and appointment booking.

        Do not invent products, prices, offers, availability, booking slots, policies, service history, vehicle details, or payment links.
        If human help is needed, ask for handoff or update the conversation state through the application layer.
        Keep text short and suitable for WhatsApp.
        """)
    String chat(
            @V("systemPrompt") String systemPrompt,
            @V("customerWhatsappId") String customerWhatsappId,
            @UserMessage String userMessage
    );
}
