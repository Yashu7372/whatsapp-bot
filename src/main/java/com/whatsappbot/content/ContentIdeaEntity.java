package com.whatsappbot.content;

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
@Table(name = "content_ideas")
public class ContentIdeaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "campaign_id")
    private UUID campaignId;

    @Column(name = "platform_code", nullable = false, length = 50)
    private String platformCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 50)
    private ContentType contentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private ContentStatus status = ContentStatus.GENERATED;

    @Column(name = "topic", columnDefinition = "TEXT", nullable = false)
    private String topic;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static ContentIdeaEntity create(TenantEntity tenant, UUID campaignId, String platformCode,
                                           ContentType contentType, String topic) {
        ContentIdeaEntity idea = new ContentIdeaEntity();
        idea.setTenant(tenant);
        idea.setCampaignId(campaignId);
        idea.setPlatformCode(platformCode);
        idea.setContentType(contentType);
        idea.setTopic(topic);
        idea.setStatus(ContentStatus.GENERATED);
        idea.setGeneratedAt(LocalDateTime.now());
        return idea;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (generatedAt == null) {
            generatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
