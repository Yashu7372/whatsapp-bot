package com.whatsappbot.domain.order;

import com.whatsappbot.domain.contact.ContactEntity;
import com.whatsappbot.domain.conversation.ConversationEntity;
import com.whatsappbot.domain.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "whatsapp_orders")
public class WhatsappOrderEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id")
    private TenantEntity tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id")
    private ConversationEntity conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id")
    private ContactEntity contact;

    @Column(name = "wa_message_id")
    private String waMessageId;

    @Column(name = "catalog_id")
    private String catalogId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "order_payload", columnDefinition = "jsonb", nullable = false)
    private String orderPayload;

    @Column(nullable = false, length = 50)
    private String status = "RECEIVED";

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();
}
