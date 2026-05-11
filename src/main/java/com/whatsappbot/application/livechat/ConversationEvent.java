package com.whatsappbot.application.livechat;

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
@Table(name = "conversation_events")
public class ConversationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private ConversationEntity conversation;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 100)
    private ConversationEventType eventType;

    @Column(name = "from_agent_id")
    private UUID fromAgentId;

    @Column(name = "to_agent_id")
    private UUID toAgentId;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static ConversationEvent create(TenantEntity tenant,
                                           ConversationEntity conversation,
                                           ConversationEventType eventType,
                                           UUID fromAgentId,
                                           UUID toAgentId,
                                           String notes) {
        ConversationEvent event = new ConversationEvent();
        event.setTenant(tenant);
        event.setConversation(conversation);
        event.setEventType(eventType);
        event.setFromAgentId(fromAgentId);
        event.setToAgentId(toAgentId);
        event.setNotes(notes);
        return event;
    }

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
