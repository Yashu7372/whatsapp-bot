package com.whatsappbot.domain.conversation;

import com.whatsappbot.domain.contact.ContactEntity;
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
@Table(name = "conversations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_conversations_tenant_contact", columnNames = {"tenant_id", "contact_id"})
})
public class ConversationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contact_id", nullable = false)
    private ContactEntity contact;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private ConversationStatus status = ConversationStatus.ACTIVE;

    @Column(name = "assigned_agent_id")
    private UUID assignedAgentId;

    @Column(name = "bot_enabled", nullable = false)
    private boolean botEnabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 30)
    private ConversationPriority priority = ConversationPriority.NORMAL;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static ConversationEntity create(TenantEntity tenant, ContactEntity contact) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setTenant(tenant);
        conversation.setContact(contact);
        conversation.setStatus(ConversationStatus.ACTIVE);
        conversation.setBotEnabled(true);
        conversation.touchLastMessageAt();
        return conversation;
    }

    public boolean canBotReply() {
        return botEnabled && status == ConversationStatus.ACTIVE;
    }

    public void requestHuman() {
        this.status = ConversationStatus.REQUESTING;
        this.botEnabled = false;
    }

    public void touchLastMessageAt() {
        this.lastMessageAt = LocalDateTime.now();
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
