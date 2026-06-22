package com.whatsappbot.domain.interactive;

import com.whatsappbot.domain.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A tenant-defined WhatsApp quick-reply button the AI is allowed to offer
 * via {@code sendReplyButtonsToCustomer}, and what happens when a customer
 * taps it. Generic across every business type — onboarding a new tenant's
 * buttons is a row insert, never a Java code change.
 *
 * @see com.whatsappbot.application.webhook.WhatsappInteractiveInboundHandler
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "whatsapp_button_replies", uniqueConstraints = {
        @UniqueConstraint(name = "uk_whatsapp_button_replies", columnNames = {"tenant_id", "button_id"})
})
public class WhatsappButtonReplyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "button_id", nullable = false, length = 120)
    private String buttonId;

    @Column(name = "button_title", nullable = false, length = 60)
    private String buttonTitle;

    @Column(name = "reply_kind", nullable = false, length = 20)
    private String replyKind = "TEXT";

    @Column(name = "reply_text", columnDefinition = "text")
    private String replyText;

    @Column(name = "tool_name", length = 120)
    private String toolName;

    @Column(name = "tool_arguments_json", columnDefinition = "text")
    private String toolArgumentsJson;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(columnDefinition = "text")
    private String description;

    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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