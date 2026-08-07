package com.whatsappbot.domain.message;

import com.whatsappbot.domain.conversation.ConversationEntity;
import com.whatsappbot.domain.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "messages")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private ConversationEntity conversation;

    @Column(name = "wa_message_id", length = 200)
    private String waMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 20)
    private MessageDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 50)
    private MessageType messageType;

    @Column(name = "text_body", columnDefinition = "text")
    private String textBody;

    @Column(name = "raw_payload", columnDefinition = "text")
    private String rawPayload;

    @Column(name = "ai_generated", nullable = false)
    private boolean aiGenerated;

    @Column(name = "sent_by_agent_id")
    private UUID sentByAgentId;

    @Column(name = "intent", length = 120)
    private String intent;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "action_type", length = 120)
    private String actionType;

    @Column(name = "buttons_json", columnDefinition = "text")
    private String buttonsJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static Message inbound(TenantEntity tenant, ConversationEntity conversation, String waMessageId,
                                  MessageType messageType, String textBody, String rawPayload) {
        Message message = new Message();
        message.setTenant(tenant);
        message.setConversation(conversation);
        message.setWaMessageId(waMessageId);
        message.setDirection(MessageDirection.INBOUND);
        message.setMessageType(messageType);
        message.setTextBody(textBody);
        message.setRawPayload(rawPayload);
        message.setAiGenerated(false);
        return message;
    }

    public static Message outboundAi(TenantEntity tenant, ConversationEntity conversation, MessageType messageType, String textBody) {
        Message message = new Message();
        message.setTenant(tenant);
        message.setConversation(conversation);
        message.setDirection(MessageDirection.OUTBOUND);
        message.setMessageType(messageType);
        message.setTextBody(textBody);
        message.setAiGenerated(true);
        return message;
    }

    public static Message outboundAgent(TenantEntity tenant,
                                        ConversationEntity conversation,
                                        MessageType messageType,
                                        String textBody,
                                        UUID agentId) {
        Message message = new Message();
        message.setTenant(tenant);
        message.setConversation(conversation);
        message.setDirection(MessageDirection.OUTBOUND);
        message.setMessageType(messageType);
        message.setTextBody(textBody);
        message.setAiGenerated(false);
        message.setSentByAgentId(agentId);
        return message;
    }

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
