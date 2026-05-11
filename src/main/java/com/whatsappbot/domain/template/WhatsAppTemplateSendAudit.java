package com.whatsappbot.domain.template;

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
@Table(name = "whatsapp_template_send_audit")
public class WhatsAppTemplateSendAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id")
    private ConversationEntity conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private WhatsAppMessageTemplate template;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", nullable = false, length = 50)
    private TemplateRecipientType recipientType;

    @Column(name = "recipient_phone_number", nullable = false, length = 50)
    private String recipientPhoneNumber;

    @Column(name = "request_components_json", columnDefinition = "text")
    private String requestComponentsJson;

    @Column(name = "graph_payload_json", columnDefinition = "text")
    private String graphPayloadJson;

    @Column(name = "graph_status_code")
    private Integer graphStatusCode;

    @Column(name = "graph_response_body", columnDefinition = "text")
    private String graphResponseBody;

    @Enumerated(EnumType.STRING)
    @Column(name = "sent_by", nullable = false, length = 50)
    private TemplateSentBy sentBy = TemplateSentBy.AI_TOOL;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static WhatsAppTemplateSendAudit create(TenantEntity tenant,
                                                   ConversationEntity conversation,
                                                   WhatsAppMessageTemplate template,
                                                   TemplateRecipientType recipientType,
                                                   String recipientPhoneNumber,
                                                   String requestComponentsJson,
                                                   String graphPayloadJson,
                                                   int graphStatusCode,
                                                   String graphResponseBody,
                                                   TemplateSentBy sentBy) {
        WhatsAppTemplateSendAudit audit = new WhatsAppTemplateSendAudit();
        audit.setTenant(tenant);
        audit.setConversation(conversation);
        audit.setTemplate(template);
        audit.setRecipientType(recipientType);
        audit.setRecipientPhoneNumber(recipientPhoneNumber);
        audit.setRequestComponentsJson(requestComponentsJson);
        audit.setGraphPayloadJson(graphPayloadJson);
        audit.setGraphStatusCode(graphStatusCode);
        audit.setGraphResponseBody(graphResponseBody);
        audit.setSentBy(sentBy);
        return audit;
    }

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
