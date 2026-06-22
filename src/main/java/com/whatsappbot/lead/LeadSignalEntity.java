package com.whatsappbot.lead;

import com.whatsappbot.domain.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "lead_signals")
public class LeadSignalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "contact_id")
    private UUID contactId;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false, length = 50)
    private LeadSignalType signalType;

    @Enumerated(EnumType.STRING)
    @Column(name = "intent_category", length = 50)
    private LeadIntentCategory intentCategory;

    @Column(name = "message_text", columnDefinition = "TEXT")
    private String messageText;

    @Column(name = "score", nullable = false)
    private double score;

    @Column(name = "platform_code", nullable = false, length = 50)
    private String platformCode = "WHATSAPP";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata = "{}";

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static LeadSignalEntity fromInbound(TenantEntity tenant, UUID contactId, UUID conversationId,
                                               String messageText, LeadIntentCategory intentCategory, double score) {
        LeadSignalEntity signal = new LeadSignalEntity();
        signal.setTenant(tenant);
        signal.setContactId(contactId);
        signal.setConversationId(conversationId);
        signal.setSignalType(LeadSignalType.INBOUND_MESSAGE);
        signal.setMessageText(messageText);
        signal.setIntentCategory(intentCategory);
        signal.setScore(score);
        signal.setPlatformCode("WHATSAPP");
        signal.setMetadata("{}");
        return signal;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        capturedAt = now;
    }
}
